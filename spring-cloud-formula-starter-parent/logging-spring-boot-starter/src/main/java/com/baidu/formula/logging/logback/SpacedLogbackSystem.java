/*
 * Copyright 2012-2018 Spring Boot Authors. All rights reserved.
 *
 * Modifications copyright (c) 2018 Baidu, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.baidu.formula.logging.logback;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.filter.ThresholdFilter;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.ConsoleAppender;
import ch.qos.logback.core.rolling.RollingFileAppender;
import ch.qos.logback.core.rolling.SizeAndTimeBasedRollingPolicy;
import ch.qos.logback.core.spi.ContextAware;
import ch.qos.logback.core.spi.LifeCycle;
import ch.qos.logback.core.util.FileSize;
import ch.qos.logback.core.util.OptionHelper;
import com.baidu.formula.logging.config.LoggingProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.logging.LogFile;
import org.springframework.boot.logging.logback.LogbackLoggingSystem;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertyResolver;
import org.springframework.core.env.PropertySourcesPropertyResolver;
import org.springframework.util.ReflectionUtils;

import java.io.File;
import java.lang.reflect.Method;
import java.nio.charset.Charset;
import java.util.Properties;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.stream.StreamSupport;

/**
 * Base logback configuration,
 * default log format references to DefaultLogbackConfiguration
 *
 * @author Bowu Dong (tq02ksu@gmail.com)
 * @see org.springframework.boot.logging.logback.DefaultLogbackConfiguration
 */
public abstract class SpacedLogbackSystem extends LogbackLoggingSystem {
    private static final Logger logger = LoggerFactory.getLogger(SpacedLogbackSystem.class);

    protected static final String LOGGER_NAME = "ai.wayz.cloud";

    protected static final String LOGGING_PATTERN_LEVEL =
            "%5p [${spring.cloud.config.name:${spring.application.name:-}},%X{X-B3-TraceId:-},%X{X-B3-SpanId:-},%X{X-Span-Export:-}]";

    protected static final String FILE_LOG_PATTERN =
            "%d [%t] %-5level %class{32}:%-4line - %msg%n%throwable";

    static final Charset DEFAULT_CHARSET = Charset.forName("UTF-8");

    public static final String KEY_ENABLED = "FORMULA_LOGGING_ENABLED";
    protected static final String DEFAULT_PATH = "log";
    protected static final String DEFAULT_FILE_SIZE = "250MB";
    protected static final int DEFAULT_MAX_HISTORY = 24 * 7;
    protected static final String DEFAULT_TOTAL_SIZE_CAP = "15GB";
    protected static final boolean DEFAULT_ADDDTIVITY = false;
    protected static final String DEFAULT_FILE_PATTERN = "%d{yyyy-MM-dd-HH}-%i";
    protected static final String DEFAULT_ERROR_FILE_PATTERN = "%d{yyyy-MM-dd}-%i";

    protected LoggerContext context;

    protected LoggingProperties properties;

    protected PropertyResolver patterns;

    protected boolean checkPassed;

    protected String appName;

    public SpacedLogbackSystem(ClassLoader classLoader) {
        super(classLoader);
    }

    protected PropertyResolver getPatternsResolver(Environment environment) {
        if (environment == null) {
            return new PropertySourcesPropertyResolver(null);
        }
        if (environment instanceof ConfigurableEnvironment) {
            PropertySourcesPropertyResolver resolver = new PropertySourcesPropertyResolver(
                    ((ConfigurableEnvironment) environment).getPropertySources());
            resolver.setIgnoreUnresolvableNestedPlaceholders(true);
            return resolver;
        }
        return environment;
    }

    protected boolean isSet(ConfigurableEnvironment environment, String property) {
        String value = environment.getProperty(property);
        return (value != null && !value.equals("false"));
    }

    protected LoggingProperties parseProperties(ConfigurableEnvironment environment) {
        LoggingProperties properties = Binder.get(environment)
                .bind(LoggingProperties.PREFIX, LoggingProperties.class)
                .orElseGet(LoggingProperties::new);

        if (isSet(environment, "trace")) {
            logger.info("debug mode, set default threshold to trace");
            properties.setThreshold("trace");
        } else if (isSet(environment, "debug")) {
            logger.info("debug mode, set default threshold to debug");
            properties.setThreshold("debug");
        }

        return properties;
    }

    protected boolean checkProperties(LoggingProperties properties) {
        return properties.isEnabled();
    }

    @SafeVarargs
    public final void root(Level level, Appender<ILoggingEvent>... appenders) {
        ch.qos.logback.classic.Logger logger = context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        if (level != null) {
            logger.setLevel(level);
        }
        for (Appender<ILoggingEvent> appender : appenders) {
            logger.addAppender(appender);
        }

        // set console log

        boolean initConsole = StreamSupport.stream(
                Spliterators.spliteratorUnknownSize(
                        logger.iteratorForAppenders(), Spliterator.ORDERED), false)
                .noneMatch(a -> a.getName().toLowerCase().contains("console"));

        if (initConsole) {
            Appender<ILoggingEvent> appender = consoleAppender();
            logger.addAppender(appender);
        }
    }

    private Appender<ILoggingEvent> consoleAppender() {
        ConsoleAppender<ILoggingEvent> appender = new ConsoleAppender<>();
        PatternLayoutEncoder encoder = new PatternLayoutEncoder();
        String logPattern = this.patterns.getProperty("logging.pattern.console", FILE_LOG_PATTERN);
        encoder.setPattern(OptionHelper.substVars(logPattern, context));
        encoder.setCharset(DEFAULT_CHARSET);
        appender.setEncoder(encoder);
        start(encoder);
        appender("console", appender);
        return appender;
    }

    protected Appender<ILoggingEvent> fileAppender(LoggingProperties loggingProperties) {
        RollingFileAppender<ILoggingEvent> appender = new RollingFileAppender<>();
        PatternLayoutEncoder encoder = new PatternLayoutEncoder();
        String logPattern = this.patterns.getProperty("logging.pattern.file", FILE_LOG_PATTERN);
        encoder.setPattern(OptionHelper.substVars(logPattern, context));
        encoder.setCharset(DEFAULT_CHARSET);
        appender.setEncoder(encoder);
        start(encoder);

        // parse path and file
        // first consider spec.path, second, default_spec.path, third logging.path
        LogFile logFile = LogFile.get(patterns);
        Properties defaultProperties = new Properties();
        if (logFile != null) {
            logFile.applyTo(defaultProperties);
        }
        String path = loggingProperties.getPath() != null ? loggingProperties.getPath() : DEFAULT_PATH;
        path = patterns.resolvePlaceholders(path);
        String file = loggingProperties.getFile() != null
                ? fileName(loggingProperties.getFile()) : fileName(appName);
        file = patterns.resolvePlaceholders(file);
        appender.setFile(path + "/" + file);
        setRollingPolicy(appender, loggingProperties, path, file);

        //  threshold config
        // error log using this block code
        ThresholdFilter thresholdFilter = new ThresholdFilter();
        if (loggingProperties.getThreshold() != null) {
            thresholdFilter.setLevel(loggingProperties.getThreshold());
            start(thresholdFilter);
            appender.addFilter(thresholdFilter);
        }

        String appenderName = loggingProperties.getAppenderName() != null ?
                loggingProperties.getAppenderName() : appName;
        appender(appenderName, appender);
        return appender;
    }

    private void setRollingPolicy(RollingFileAppender<ILoggingEvent> appender, LoggingProperties loggingProperties,
                                  String path, String file) {
        SizeAndTimeBasedRollingPolicy<ILoggingEvent> rollingPolicy = new SizeAndTimeBasedRollingPolicy<>();
        String dirName = new File(path, file).getParent();
        String rollingFilePattern = loggingProperties.getRollingFilePattern() != null ?
                loggingProperties.getRollingFilePattern() : DEFAULT_FILE_PATTERN;
        String fileNamePattern = dirName + File.separator + file.split(".log")[0] + "-" +
                rollingFilePattern + ".log";
        rollingPolicy.setFileNamePattern(fileNamePattern);
        String maxFileSize = loggingProperties.getMaxFileSize() != null ? loggingProperties.getMaxFileSize() :
                DEFAULT_FILE_SIZE;
        setMaxFileSize(rollingPolicy, maxFileSize);

        // total size cap
        String totalSizeCap = loggingProperties.getTotalSizeCap() != null ? loggingProperties.getTotalSizeCap() :
                DEFAULT_TOTAL_SIZE_CAP;

        setTotalSizeCap(rollingPolicy, totalSizeCap);

        int maxHistory = loggingProperties.getMaxHistory() != null ? loggingProperties.getMaxHistory() :
                DEFAULT_MAX_HISTORY;
        rollingPolicy.setMaxHistory(maxHistory);

        appender.setRollingPolicy(rollingPolicy);
        // 启动时会尝试清除超过数量的日志文件
        rollingPolicy.setCleanHistoryOnStart(true);
        rollingPolicy.setParent(appender);
//        rollingPolicy.setCleanHistoryOnStart(true);
        start(rollingPolicy);
    }

    private void setTotalSizeCap(SizeAndTimeBasedRollingPolicy<ILoggingEvent> rollingPolicy,
                                 String totalSizeCap) {
        try {
            rollingPolicy.setContext(context);
            rollingPolicy.setTotalSizeCap(FileSize.valueOf(totalSizeCap));
        } catch (NoSuchMethodError ex) {
            // Logback < 1.1.8 used String configuration
            // copy from setMaxFileSize
            Method method = ReflectionUtils.findMethod(
                    SizeAndTimeBasedRollingPolicy.class, "setTotalSizeCap", String.class);
            ReflectionUtils.invokeMethod(method, rollingPolicy, totalSizeCap);
        }
    }

    private void setMaxFileSize(SizeAndTimeBasedRollingPolicy<ILoggingEvent> rollingPolicy,
                                String maxFileSize) {
        try {
            rollingPolicy.setMaxFileSize(FileSize.valueOf(maxFileSize));
        } catch (NoSuchMethodError ex) {
            // Logback < 1.1.8 used String configuration
            Method method = ReflectionUtils.findMethod(
                    SizeAndTimeBasedRollingPolicy.class, "setMaxFileSize", String.class);
            ReflectionUtils.invokeMethod(method, rollingPolicy, maxFileSize);
        }
    }

    public void appender(String name, Appender<?> appender) {
        appender.setName(name);
        start(appender);
    }

    protected String fileName(String key) {
        return key.endsWith(".log") ? key : key + ".log";
    }

    public void start(LifeCycle lifeCycle) {
        if (lifeCycle instanceof ContextAware) {
            ((ContextAware) lifeCycle).setContext(context);
        }
        lifeCycle.start();
    }
}
