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
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.CoreConstants;
import com.baidu.formula.logging.config.LoggingProperties;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.logging.LogFile;
import org.springframework.boot.logging.LoggingInitializationContext;
import org.springframework.boot.logging.logback.ColorConverter;
import org.springframework.boot.logging.logback.ExtendedWhitespaceThrowableProxyConverter;
import org.springframework.boot.logging.logback.WhitespaceThrowableProxyConverter;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.Map;

/**
 * Yaml configuration based logback system implementation.
 * <p>
 * ruleRegistry configuration references to DefaultLogbackConfiguration
 *
 * @author Bowu Dong (tq02ksu@gmail.com)
 * @see org.springframework.boot.logging.logback.DefaultLogbackConfiguration
 */
public class FormulaLogbackSystem extends SpacedLogbackSystem {
    public static final org.slf4j.Logger logger = LoggerFactory.getLogger(FormulaLogbackSystem.class);

    public FormulaLogbackSystem(ClassLoader classLoader) {
        super(classLoader);
    }

    protected void loadSpaces(LoggingInitializationContext initializationContext) {
        if (!checkPassed) {
            context.putProperty(KEY_ENABLED, "false");
            return;
        }
        context.putProperty(KEY_ENABLED, "true");
        // set conversion rule
        Map<String, String> ruleRegistry = (Map) context.getObject(CoreConstants.PATTERN_RULE_REGISTRY);
        if (ruleRegistry == null) {
            ruleRegistry = new HashMap<>();
            context.putObject(CoreConstants.PATTERN_RULE_REGISTRY, ruleRegistry);
        }
        // put the new rule into the rule registry
        // <conversionRule conversionWord="clr"
        //      converterClass="org.springframework.boot.logging.logback.ColorConverter" />
        // <conversionRule conversionWord="wex"
        //      converterClass="org.springframework.boot.logging.logback.WhitespaceThrowableProxyConverter" />
        // <conversionRule conversionWord="wEx"
        //      converterClass="org.springframework.boot.logging.logback.ExtendedWhitespaceThrowableProxyConverter" />
        ruleRegistry.put("clr", ColorConverter.class.getName());
        ruleRegistry.put("wex", WhitespaceThrowableProxyConverter.class.getName());
        ruleRegistry.put("wEx", ExtendedWhitespaceThrowableProxyConverter.class.getName());

        if (appName == null) {
            appName = "unknown-app";
        }
        String rootLevel = initializationContext.getEnvironment().getProperty("logging.level.root");
        if (rootLevel == null) {
            rootLevel = properties.getThreshold();
        }
        Appender<ILoggingEvent> appender = fileAppender(properties);
        Appender<ILoggingEvent> errorLogAppender = fileAppender(getErrorLogProperties(appName));
        root(Level.valueOf(rootLevel), appender, errorLogAppender);

        context.setPackagingDataEnabled(true);
    }

    @Override
    protected void loadDefaults(LoggingInitializationContext initializationContext, LogFile logFile) {
        beforeLoadSpaces(initializationContext);
        super.loadDefaults(initializationContext, logFile);
        loadSpaces(initializationContext);
    }

    @Override
    protected void loadConfiguration(LoggingInitializationContext initializationContext, String location, LogFile logFile) {
        beforeLoadSpaces(initializationContext);
        super.loadConfiguration(initializationContext, location, logFile);
        loadSpaces(initializationContext);
    }

    private void beforeLoadSpaces(LoggingInitializationContext initializationContext) {
        ConfigurableEnvironment environment = (ConfigurableEnvironment) initializationContext.getEnvironment();

//        properties = parseProperties(environment);
        properties = Binder.get(environment)
                .bind(LoggingProperties.PREFIX, LoggingProperties.class)
                .orElseGet(LoggingProperties::new);
        context = (LoggerContext) LoggerFactory.getILoggerFactory();

        patterns = getPatternsResolver(environment);

        checkPassed = checkProperties(properties);

        if (!checkPassed) {
            logger.info("DISABLED Formula Logging ...");
            return;
        }

        String applicationName = environment.getProperty("spring.application.name");
        String configClientName = environment.getProperty("spring.cloud.config.name");
        appName = applicationName;

        MapPropertySource defaultProperties;
        MutablePropertySources propertySources = environment.getPropertySources();
        if (propertySources.contains("defaultProperties")) {
            defaultProperties = (MapPropertySource) propertySources.get("defaultProperties");

        } else {
            Map<String, Object> map = new HashMap<>();
            map.put("spring.application.name", configClientName);
            defaultProperties = new MapPropertySource("defaultProperties", map);
            environment.getPropertySources().addLast(defaultProperties);
        }

        if (StringUtils.isEmpty(applicationName) && StringUtils.hasLength(configClientName)) {
            defaultProperties.getSource().put("spring.application.name", configClientName);
        }

        String result = patterns.resolvePlaceholders(LOGGING_PATTERN_LEVEL);
        defaultProperties.getSource().put("logging.pattern.level", result);

        String patternResult = patterns.resolvePlaceholders(FILE_LOG_PATTERN);
        defaultProperties.getSource().put("logging.pattern.console", patternResult);

        defaultProperties.getSource().put("logging.level.root", "info");
    }

    private LoggingProperties getErrorLogProperties(String appName) {
        LoggingProperties errorLogProperties = new LoggingProperties();
        errorLogProperties.setThreshold("error");
        errorLogProperties.setFile(properties.getFile() == null ?
                appName + "-error.log" : properties.getFile() + "-error.log");
        errorLogProperties.setPath(properties.getPath());
        errorLogProperties.setRollingFilePattern(DEFAULT_ERROR_FILE_PATTERN);
        errorLogProperties.setMaxHistory(7);
        errorLogProperties.setAppenderName(appName + "-error");
        return errorLogProperties;
    }
}
