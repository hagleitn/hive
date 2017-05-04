/*
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hive.storage.jdbc.conf;

import java.io.IOException;
import org.apache.hadoop.hive.shims.ShimLoader;
import org.apache.hadoop.hive.ql.metadata.Hive;
import org.apache.hadoop.hive.conf.HiveConf;
import org.apache.hive.storage.jdbc.conf.DatabaseType;

import org.apache.hadoop.conf.Configuration;

import org.apache.hive.storage.jdbc.QueryConditionBuilder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumSet;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Properties;

/**
 * Main configuration handler class
 */
public class JdbcStorageConfigManager {

  private static final Logger LOGGER = LoggerFactory.getLogger(JdbcStorageConfigManager.class);
  public static final String CONFIG_PREFIX = "hive.sql";
  public static final String CONFIG_PWD = CONFIG_PREFIX + ".dbcp.password";
  private static final EnumSet<JdbcStorageConfig> DEFAULT_REQUIRED_PROPERTIES =
    EnumSet.of(JdbcStorageConfig.DATABASE_TYPE,
        JdbcStorageConfig.QUERY);

  private JdbcStorageConfigManager() {
  }

  public static void copyConfigurationToJob(Properties props, Map<String, String> jobProps) {
    checkRequiredPropertiesAreDefined(props);
    resolveMetadata(props);
    for (Entry<Object, Object> entry : props.entrySet()) {
      if (!String.valueOf(entry.getKey()).equals(CONFIG_PWD)) {
        jobProps.put(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
      }
    }
  }

  public static void copySecretsToJob(Properties props, Map<String, String> jobSecrets) {
    checkRequiredPropertiesAreDefined(props);
    resolveMetadata(props);
    String secret = props.getProperty(CONFIG_PWD);
    if (secret != null) {
      jobSecrets.put(CONFIG_PWD, secret);
    }
  }

  public static Configuration convertPropertiesToConfiguration(Properties props) {
    checkRequiredPropertiesAreDefined(props);
    resolveMetadata(props);
    Configuration conf = new Configuration();

    for (Entry<Object, Object> entry : props.entrySet()) {
      conf.set(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
    }

    return conf;
  }


  private static void checkRequiredPropertiesAreDefined(Properties props) {
    for (JdbcStorageConfig configKey : DEFAULT_REQUIRED_PROPERTIES) {
      String propertyKey = configKey.getPropertyName();
      if ((props == null) || (!props.containsKey(propertyKey)) || (isEmptyString(props.getProperty(propertyKey)))) {
        throw new IllegalArgumentException("Property " + propertyKey + " is required.");
      }
    }

    DatabaseType dbType = DatabaseType.valueOf(props.getProperty(JdbcStorageConfig.DATABASE_TYPE.getPropertyName()));
    CustomConfigManager configManager = CustomConfigManagerFactory.getCustomConfigManagerFor(dbType);
    configManager.checkRequiredProperties(props);
  }


  public static String getConfigValue(JdbcStorageConfig key, Configuration config) {
    return config.get(key.getPropertyName());
  }


  public static String getQueryToExecute(Configuration config) {
    String query = config.get(JdbcStorageConfig.QUERY.getPropertyName());
    String hiveFilterCondition = QueryConditionBuilder.getInstance().buildCondition(config);
    if ((hiveFilterCondition != null) && (!hiveFilterCondition.trim().isEmpty())) {
      query = query + " WHERE " + hiveFilterCondition;
    }

    return query;
  }


  private static boolean isEmptyString(String value) {
    return ((value == null) || (value.trim().isEmpty()));
  }

  private static void resolveMetadata(Properties props) {
    try {
      DatabaseType dbType = DatabaseType.valueOf(
        props.getProperty(JdbcStorageConfig.DATABASE_TYPE.getPropertyName()));

      LOGGER.debug("Resolving db type: {}", dbType.toString());

      if (dbType == DatabaseType.METASTORE) {
        HiveConf hconf = Hive.get().getConf();
        props.setProperty(JdbcStorageConfig.JDBC_URL.getPropertyName(),
            getMetastoreConnectionURL(hconf));
        props.setProperty(JdbcStorageConfig.JDBC_DRIVER_CLASS.getPropertyName(),
            getMetastoreDriver(hconf));

        String user = getMetastoreJdbcUser(hconf);
        if (user != null) {
          props.setProperty("hive.sql.dbcp.username", user);
        }

        String pwd = getMetastoreJdbcPasswd(hconf);
        if (pwd != null) {
          props.setProperty(CONFIG_PWD, pwd);
        }
        props.setProperty(JdbcStorageConfig.DATABASE_TYPE.getPropertyName(),
            getMetastoreDatabaseType(hconf));
      }
    } catch (Exception e) {}
  }

  private static String getMetastoreDatabaseType(HiveConf conf) {
    return conf.getVar(HiveConf.ConfVars.METASTOREDBTYPE);
  }

  private static String getMetastoreConnectionURL(HiveConf conf) {
    return conf.getVar(HiveConf.ConfVars.METASTORECONNECTURLKEY);
  }

  private static String getMetastoreDriver(HiveConf conf) {
    return conf.getVar(HiveConf.ConfVars.METASTORE_CONNECTION_DRIVER);
  }

  private static String getMetastoreJdbcUser(HiveConf conf) {
    return conf.getVar(HiveConf.ConfVars.METASTORE_CONNECTION_USER_NAME);
  }

  private static String getMetastoreJdbcPasswd(HiveConf conf) {
    try {
      return ShimLoader.getHadoopShims().getPassword(conf,
          HiveConf.ConfVars.METASTOREPWD.varname);
    } catch (IOException io) {
    }
    return null;
  }
}
