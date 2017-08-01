/*
 * Copyright (C) 2016 to the original authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package org.springframework.cloud.kubernetes.config;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.yaml.SpringProfileDocumentMatcher;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.util.StringUtils;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

public class ConfigMapPropertySource extends MapPropertySource {
    private static final Log LOG = LogFactory.getLog(ConfigMapPropertySource.class);

    private static final String SCK_CONFIG_NAME = "spring.cloud.kubernetes.config.name";
    private static final String SPRING_CONFIG_LOCATION = "spring.config.location";
    private static final String SPRING_CONFIG_NAME = "spring.config.name";

    private static final Set<String> CONFIG_FILES_PREFIXES = new HashSet<>();

    static {
        CONFIG_FILES_PREFIXES.add("application");

        // first use SCK config name if available
        String configName = System.getProperty(SCK_CONFIG_NAME);
        if(configName != null) {
            LOG.debug(SCK_CONFIG_NAME + "=" + configName);
            CONFIG_FILES_PREFIXES.add(configName);
        }

        // if Spring config name is set, add it to prefixes to look for as well
        configName = System.getProperty(SPRING_CONFIG_NAME);
        if (configName != null) {
            LOG.debug(SPRING_CONFIG_NAME + "=" + configName);
            CONFIG_FILES_PREFIXES.add(configName);
        }
    }

    private abstract static class SpringProperty {

        private final String name;

        private SpringProperty(String name) {
            this.name = name;
        }

        static SpringProperty from(String fullName) {
            final SpringProperty defaultProp = new SpringProperty(fullName) {
                @Override
                Function<String, Map<String, String>> mapExtractorFor(String[] profiles) {
                    return s -> Collections.singletonMap(fullName, s);
                }
            };

            // first check if the property matches
            final String name = StringUtils.stripFilenameExtension(fullName);
            if (!CONFIG_FILES_PREFIXES.contains(name)) {
                return defaultProp;
            } else {
                final String extension = StringUtils.getFilenameExtension(fullName);
                switch (extension) {
                    case "yml":
                    case "yaml":
                        return new SpringProperty(fullName) {
                            @Override
                            Function<String, Map<String, String>> mapExtractorFor(String[] profiles) {
                                return yamlParserGenerator(profiles).andThen(PROPERTIES_TO_MAP);
                            }
                        };
                    case "properties":
                        return new SpringProperty(fullName) {
                            @Override
                            Function<String, Map<String, String>> mapExtractorFor(String[] profiles) {
                                return KEY_VALUE_TO_PROPERTIES.andThen(PROPERTIES_TO_MAP);
                            }
                        };
                    default:
                        return defaultProp;
                }
            }
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            SpringProperty that = (SpringProperty) o;

            return name.equals(that.name);
        }

        @Override
        public int hashCode() {
            return name.hashCode();
        }

        abstract Function<String, Map<String, String>> mapExtractorFor(String[] profiles);
    }

    private static final String PREFIX = "configmap";

    public ConfigMapPropertySource(KubernetesClient client, String name) {
        this(client, name, null);
    }

    public ConfigMapPropertySource(KubernetesClient client, String name, String[] profiles) {
        this(client, name, null, profiles);
    }

    public ConfigMapPropertySource(KubernetesClient client, String name, String namespace, String[] profiles) {
        super(getName(client, name, namespace), asObjectMap(getData(client, name, namespace, profiles)));
    }

    private static String getName(KubernetesClient client, String name, String namespace) {
        return new StringBuilder()
            .append(PREFIX)
            .append(Constants.PROPERTY_SOURCE_NAME_SEPARATOR)
            .append(name)
            .append(Constants.PROPERTY_SOURCE_NAME_SEPARATOR)
            .append(namespace == null || namespace.isEmpty() ? client.getNamespace() : namespace)
            .toString();
    }

    private static Map<String, String> getData(KubernetesClient client, String name, String namespace, String[] profiles) {
        Map<String, String> result = new HashMap<>();
        try {
            ConfigMap map = namespace == null || namespace.isEmpty()
                ? client.configMaps().withName(name).get()
                : client.configMaps().inNamespace(namespace).withName(name).get();

            if (map != null) {
                for (Map.Entry<String, String> entry : map.getData().entrySet()) {
                    String key = entry.getKey();
                    String value = entry.getValue();
                    final Map<String, String> propertiesAsMap = SpringProperty.from(key).mapExtractorFor(profiles)
                        .apply(value);
                    LOG.debug(key + " => " + propertiesAsMap);
                    result.putAll(propertiesAsMap);
                }
            }
        } catch (Exception e) {
            LOG.warn("Can't read configMap with name: [" + name + "] in namespace:[" + namespace + "]. Ignoring", e);
        }
        return result;
    }

    private static Map<String, Object> asObjectMap(Map<String, String> source) {
        return source.entrySet()
            .stream()
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    private static final Function<String, Properties> yamlParserGenerator(final String[] profiles) {
        return s -> {
            YamlPropertiesFactoryBean yamlFactory = new YamlPropertiesFactoryBean();
            if (profiles == null) {
                yamlFactory.setDocumentMatchers(new SpringProfileDocumentMatcher());
            } else {
                yamlFactory.setDocumentMatchers(new SpringProfileDocumentMatcher(profiles));
            }
            yamlFactory.setResources(new ByteArrayResource(s.getBytes()));
            return yamlFactory.getObject();
        };
    }

    private static final Function<String, Properties> KEY_VALUE_TO_PROPERTIES = s -> {
        Properties properties = new Properties();
        try {
            properties.load(new ByteArrayInputStream(s.getBytes()));
            return properties;
        } catch (IOException e) {
            throw new IllegalArgumentException();
        }
    };

    private static final Function<Properties, Map<String, String>> PROPERTIES_TO_MAP = p -> p.entrySet().stream()
        .collect(Collectors.toMap(
            e -> String.valueOf(e.getKey()),
            e -> String.valueOf(e.getValue())));


}
