/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.whirr.service.hbase;

import static org.apache.whirr.service.RolePredicates.role;

import com.google.common.base.Charsets;
import com.google.common.base.Joiner;
import com.google.common.io.Files;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.util.Map.Entry;
import java.util.Properties;

import org.apache.whirr.net.DnsUtil;
import org.apache.whirr.service.Cluster;
import org.apache.whirr.service.Cluster.Instance;
import org.apache.whirr.service.ClusterActionEvent;
import org.apache.whirr.service.ClusterSpec;
import org.apache.whirr.service.ComputeServiceContextBuilder;
import org.apache.whirr.service.hadoop.HadoopProxy;
import org.apache.whirr.service.jclouds.FirewallSettings;
import org.apache.whirr.service.zookeeper.ZooKeeperCluster;
import org.jclouds.compute.ComputeServiceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HBaseMasterClusterActionHandler extends HBaseClusterActionHandler {

  private static final Logger LOG =
    LoggerFactory.getLogger(HBaseMasterClusterActionHandler.class);

  public static final String ROLE = "hbase-master";

  public static final int MASTER_PORT = 60000;
  public static final int MASTER_WEB_UI_PORT = 60010;

  @Override
  public String getRole() {
    return ROLE;
  }

  @Override
  protected void beforeBootstrap(ClusterActionEvent event) throws IOException {
    ClusterSpec clusterSpec = event.getClusterSpec();
    addRunUrl(event, "util/configure-hostnames",
      HBaseConstants.PARAM_PROVIDER, clusterSpec.getProvider());
    addRunUrl(event, "sun/java/install");
    String hbaseInstallRunUrl = getConfiguration(clusterSpec).getString(
      HBaseConstants.KEY_INSTALL_RUNURL, HBaseConstants.SCRIPT_INSTALL);
    String tarurl = getConfiguration(clusterSpec).getString(
      HBaseConstants.KEY_TARBALL_URL);
    addRunUrl(event, hbaseInstallRunUrl,
      HBaseConstants.PARAM_PROVIDER, clusterSpec.getProvider(),
      HBaseConstants.PARAM_TARBALL_URL, tarurl);
    event.setTemplateBuilderStrategy(new HBaseTemplateBuilderStrategy());
  }

  @Override
  protected void beforeConfigure(ClusterActionEvent event) throws IOException, InterruptedException {
    ClusterSpec clusterSpec = event.getClusterSpec();
    Cluster cluster = event.getCluster();

    LOG.info("Authorizing firewall");
    Instance instance = cluster.getInstanceMatching(role(ROLE));
    InetAddress masterPublicAddress = instance.getPublicAddress();

    ComputeServiceContext computeServiceContext =
      ComputeServiceContextBuilder.build(clusterSpec);
    FirewallSettings.authorizeIngress(computeServiceContext, instance, clusterSpec,
      MASTER_WEB_UI_PORT);
    FirewallSettings.authorizeIngress(computeServiceContext, instance, clusterSpec,
      MASTER_PORT);

    String hbaseConfigureRunUrl = getConfiguration(clusterSpec).getString(
      HBaseConstants.KEY_CONFIGURE_RUNURL, HBaseConstants.SCRIPT_POST_CONFIGURE);
    String master = DnsUtil.resolveAddress(masterPublicAddress.getHostAddress());
    String quorum = ZooKeeperCluster.getHosts(cluster);
    String tarurl = getConfiguration(clusterSpec).getString(
      HBaseConstants.KEY_TARBALL_URL);
    addRunUrl(event, hbaseConfigureRunUrl, ROLE,
      HBaseConstants.PARAM_MASTER, master,
      HBaseConstants.PARAM_QUORUM, quorum,
      HBaseConstants.PARAM_PROVIDER, clusterSpec.getProvider(),
      HBaseConstants.PARAM_TARBALL_URL, tarurl);
  }

  @Override
  protected void afterConfigure(ClusterActionEvent event) throws IOException {
    ClusterSpec clusterSpec = event.getClusterSpec();
    Cluster cluster = event.getCluster();

    // TODO: wait for regionservers to come up?

    LOG.info("Completed configuration of {}", clusterSpec.getClusterName());
    Instance instance = cluster.getInstanceMatching(role(ROLE));
    InetAddress masterPublicAddress = instance.getPublicAddress();

    LOG.info("Web UI available at http://{}",
      DnsUtil.resolveAddress(masterPublicAddress.getHostAddress()));
    String quorum = ZooKeeperCluster.getHosts(cluster);
    Properties config = createClientSideProperties(masterPublicAddress, quorum);
    createClientSideHadoopSiteFile(clusterSpec, config);
    createProxyScript(clusterSpec, cluster);
    event.setCluster(new Cluster(cluster.getInstances(), config));
  }

  private Properties createClientSideProperties(InetAddress master, String quorum) throws IOException {
    Properties config = new Properties();
    config.setProperty(HBaseConstants.PROP_HBASE_ZOOKEEPER_QUORUM, quorum);
    config.setProperty(HBaseConstants.PROP_HBASE_ZOOKEEPER_CLIENTPORT, "2181");
    config.setProperty("hadoop.socks.server", "localhost:6666");
    config.setProperty("hadoop.rpc.socket.factory.class.default", "org.apache.hadoop.net.SocksSocketFactory");
    return config;
  }

  private void createClientSideHadoopSiteFile(ClusterSpec clusterSpec, Properties config) {
    File configDir = getConfigDir(clusterSpec);
    File hbaseSiteFile = new File(configDir, HBaseConstants.FILE_HBASE_SITE_XML);
    try {
      Files.write(generateHBaseConfigurationFile(config), hbaseSiteFile, Charsets.UTF_8);
      LOG.info("Wrote HBase site file {}", hbaseSiteFile);
    } catch (IOException e) {
      LOG.error("Problem writing HBase site file {}", hbaseSiteFile, e);
    }
  }

  private File getConfigDir(ClusterSpec clusterSpec) {
    File configDir = new File(new File(System.getProperty("user.home")), ".whirr");
    configDir = new File(configDir, clusterSpec.getClusterName());
    configDir.mkdirs();
    return configDir;
  }

  private CharSequence generateHBaseConfigurationFile(Properties config) {
    StringBuilder sb = new StringBuilder();
    sb.append("<?xml version=\"1.0\"?>\n");
    sb.append("<?xml-stylesheet type=\"text/xsl\" href=\"configuration.xsl\"?>\n");
    sb.append("<configuration>\n");
    for (Entry<Object, Object> entry : config.entrySet()) {
      sb.append("  <property>\n");
      sb.append("    <name>").append(entry.getKey()).append("</name>\n");
      sb.append("    <value>").append(entry.getValue()).append("</value>\n");
      sb.append("  </property>\n");
    }
    sb.append("</configuration>\n");
    return sb;
  }

  private void createProxyScript(ClusterSpec clusterSpec, Cluster cluster) {
    File configDir = getConfigDir(clusterSpec);
    File hbaseProxyFile = new File(configDir, "hbase-proxy.sh");
    try {
      HadoopProxy proxy = new HadoopProxy(clusterSpec, cluster);
      InetAddress master = HBaseCluster.getMasterPublicAddress(cluster);
      String script = String.format("echo 'Running proxy to HBase cluster at %s. " +
        "Use Ctrl-c to quit.'\n",
        DnsUtil.resolveAddress(master.getHostAddress()))
        + Joiner.on(" ").join(proxy.getProxyCommand());
      Files.write(script, hbaseProxyFile, Charsets.UTF_8);
      LOG.info("Wrote HBase proxy script {}", hbaseProxyFile);
    } catch (IOException e) {
      LOG.error("Problem writing HBase proxy script {}", hbaseProxyFile, e);
    }
  }

}
