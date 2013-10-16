/**
 * Copyright (C) FuseSource, Inc.
 * http://fusesource.com
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
package org.fusesource.fabric.service;

import static org.apache.felix.scr.annotations.ReferenceCardinality.OPTIONAL_MULTIPLE;
import static org.fusesource.fabric.zookeeper.utils.ZooKeeperUtils.exists;
import static org.fusesource.fabric.zookeeper.utils.ZooKeeperUtils.getChildren;
import static org.fusesource.fabric.zookeeper.utils.ZooKeeperUtils.getSubstitutedData;
import static org.fusesource.fabric.zookeeper.utils.ZooKeeperUtils.getSubstitutedPath;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.curator.framework.CuratorFramework;
import org.apache.felix.scr.annotations.Activate;
import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.Deactivate;
import org.apache.felix.scr.annotations.Reference;
import org.apache.felix.scr.annotations.ReferencePolicy;
import org.apache.felix.scr.annotations.Service;
import org.codehaus.jackson.map.DeserializationConfig;
import org.codehaus.jackson.map.ObjectMapper;
import org.fusesource.fabric.api.Container;
import org.fusesource.fabric.api.ContainerAutoScaler;
import org.fusesource.fabric.api.ContainerAutoScalerFactory;
import org.fusesource.fabric.api.Containers;
import org.fusesource.fabric.api.ContainerProvider;
import org.fusesource.fabric.api.CreateContainerBasicMetadata;
import org.fusesource.fabric.api.CreateContainerBasicOptions;
import org.fusesource.fabric.api.CreateContainerMetadata;
import org.fusesource.fabric.api.CreateContainerOptions;
import org.fusesource.fabric.api.CreationStateListener;
import org.fusesource.fabric.api.DataStore;
import org.fusesource.fabric.api.FabricException;
import org.fusesource.fabric.api.FabricRequirements;
import org.fusesource.fabric.api.FabricService;
import org.fusesource.fabric.api.FabricStatus;
import org.fusesource.fabric.api.NullCreationStateListener;
import org.fusesource.fabric.api.PatchService;
import org.fusesource.fabric.api.PortService;
import org.fusesource.fabric.api.Profile;
import org.fusesource.fabric.api.ProfileRequirements;
import org.fusesource.fabric.api.Version;
import org.fusesource.fabric.api.jcip.GuardedBy;
import org.fusesource.fabric.api.jcip.ThreadSafe;
import org.fusesource.fabric.api.scr.AbstractComponent;
import org.fusesource.fabric.api.scr.ValidatingReference;
import org.fusesource.fabric.internal.ContainerImpl;
import org.fusesource.fabric.internal.DataStoreHelpers;
import org.fusesource.fabric.internal.ProfileImpl;
import org.fusesource.fabric.internal.VersionImpl;
import org.fusesource.fabric.utils.Constants;
import org.fusesource.fabric.utils.SystemProperties;
import org.fusesource.fabric.zookeeper.ZkPath;
import org.fusesource.fabric.zookeeper.utils.ZooKeeperUtils;
import org.osgi.service.cm.Configuration;
import org.osgi.service.cm.ConfigurationAdmin;
import org.osgi.service.component.ComponentContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Strings;

import java.util.Collection;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@ThreadSafe
@Component(name = "org.fusesource.fabric.service", description = "Fabric Service")
@Service(FabricService.class)
public final class FabricServiceImpl extends AbstractComponent implements FabricService {

    public static final String REQUIREMENTS_JSON_PATH = "/fabric/configs/org.fusesource.fabric.requirements.json";
    public static final String JVM_OPTIONS_PATH = "/fabric/configs/org.fusesource.fabric.containers.jvmOptions";

    private static final Logger LOGGER = LoggerFactory.getLogger(FabricServiceImpl.class);

    @Reference(referenceInterface = CuratorFramework.class)
    private final ValidatingReference<CuratorFramework> curator = new ValidatingReference<CuratorFramework>();
    @Reference(referenceInterface = DataStore.class)
    private final ValidatingReference<DataStore> dataStore = new ValidatingReference<DataStore>();
    @Reference(referenceInterface = PortService.class)
    private final ValidatingReference<PortService> portService = new ValidatingReference<PortService>();
    @Reference(referenceInterface = ConfigurationAdmin.class)
    private final ValidatingReference<ConfigurationAdmin> configAdmin = new ValidatingReference<ConfigurationAdmin>();
    @Reference(referenceInterface = ContainerProvider.class, bind = "bindProvider", unbind = "unbindProvider", cardinality = OPTIONAL_MULTIPLE, policy = ReferencePolicy.DYNAMIC)
    @GuardedBy("ConcurrentHashMap") private final Map<String, ContainerProvider> providers = new ConcurrentHashMap<String, ContainerProvider>();

    @GuardedBy("this") private String defaultRepo = FabricService.DEFAULT_REPO_URI;

    @Activate
    void activate(ComponentContext context) {
        activateComponent();
    }

    @Deactivate
    void deactivate() {
        deactivateComponent();
    }

    // FIXME public access on the impl
    public CuratorFramework getCurator() {
        assertValid();
        return curator.get();
    }

    @Override
    public DataStore getDataStore() {
        return dataStore.get();
    }

    public String getDefaultRepo() {
        synchronized (this) {
            return defaultRepo;
        }
    }

    public void setDefaultRepo(String defaultRepo) {
        synchronized (this) {
            this.defaultRepo = defaultRepo;
        }
    }

    @Override
    public PortService getPortService() {
        assertValid();
        return portService.get();
    }

    @Override
    public Container getCurrentContainer() {
        assertValid();
        String name = getCurrentContainerName();
        return getContainer(name);
    }

    @Override
    public String getCurrentContainerName() {
        assertValid();
        // TODO is there any other way to find this?
        return System.getProperty(SystemProperties.KARAF_NAME);
    }

    @Override
    public void trackConfiguration(Runnable callback) {
        assertValid();
        getDataStore().trackConfiguration(callback);
    }

    @Override
    public void unTrackConfiguration(Runnable callback) {
        assertValid();
        getDataStore().untrackConfiguration(callback);
    }

    @Override
    public Container[] getContainers() {
        assertValid();
        Map<String, Container> containers = new HashMap<String, Container>();
        List<String> containerIds = getDataStore().getContainers();
        for (String containerId : containerIds) {
            String parentId = getDataStore().getContainerParent(containerId);
            if (parentId.isEmpty()) {
                if (!containers.containsKey(containerId)) {
                    Container container = new ContainerImpl(null, containerId, this);
                    containers.put(containerId, container);
                }
            } else {
                Container parent = containers.get(parentId);
                if (parent == null) {
                    parent = new ContainerImpl(null, parentId, this);
                    containers.put(parentId, parent);
                }
                Container container = new ContainerImpl(parent, containerId, this);
                containers.put(containerId, container);
            }
        }
        return containers.values().toArray(new Container[containers.size()]);
    }

    @Override
    public Container getContainer(String name) {
        assertValid();
        if (getDataStore().hasContainer(name)) {
            Container parent = null;
            String parentId = getDataStore().getContainerParent(name);
            if (parentId != null && !parentId.isEmpty()) {
                parent = getContainer(parentId);
            }
            return new ContainerImpl(parent, name, this);
        }
        throw new FabricException("Container '" + name + "' does not exist");
    }

    @Override
    public void startContainer(String containerId) {
        startContainer(containerId, false);
    }

    public void startContainer(String containerId, boolean force) {
        assertValid();
        Container container = getContainer(containerId);
        if (container != null) {
            startContainer(container, force);
        }
    }

    public void startContainer(Container container) {
        startContainer(container, false);
    }

    public void startContainer(Container container, boolean force) {
        assertValid();
        LOGGER.info("Starting container {}", container.getId());
        ContainerProvider provider = getProvider(container);
        if (force || !container.isAlive()) {
            provider.start(container);
        }
    }

    @Override
    public void stopContainer(String containerId) {
        stopContainer(containerId, false);
    }

    public void stopContainer(String containerId, boolean force) {
        assertValid();
        Container container = getContainer(containerId);
        if (container != null) {
            stopContainer(container, force);
        }
    }

    public void stopContainer(Container container) {
        stopContainer(container, false);
    }

    public void stopContainer(Container container, boolean force) {
        assertValid();
        LOGGER.info("Stopping container {}", container.getId());
        ContainerProvider provider = getProvider(container);
        if (force || container.isAlive()) {
            provider.stop(container);
        }
    }

    @Override
    public void destroyContainer(String containerId) {
        destroyContainer(containerId, false);
    }

    public void destroyContainer(String containerId, boolean force) {
        assertValid();
        Container container = getContainer(containerId);
        if (container != null) {
            destroyContainer(container, force);
        }
    }

    @Override
    public void destroyContainer(Container container) {
        destroyContainer(container, false);
    }

    public void destroyContainer(Container container, boolean force) {
        assertValid();
        String containerId = container.getId();
        LOGGER.info("Destroying container {}", containerId);
        ContainerProvider provider = getProvider(container, true);
        if (provider == null && !force) {
            // Should throw an exception
            getProvider(container);
        }
        if (provider != null) {
            provider.stop(container);
            provider.destroy(container);
        }
        try {
            portService.get().unregisterPort(container);
            getDataStore().deleteContainer(container.getId());
        } catch (Exception e) {
            LOGGER.warn("Failed to cleanup container {} entries due to: {}. This will be ignored.", containerId, e.getMessage());
        }
    }

    private ContainerProvider getProvider(Container container) {
        return getProvider(container, false);
    }

    private ContainerProvider getProvider(Container container, boolean returnNull) {
        CreateContainerMetadata metadata = container.getMetadata();
        String type = metadata != null ? metadata.getCreateOptions().getProviderType() : null;
        if (type == null) {
            if (returnNull) {
                return null;
            }
            throw new UnsupportedOperationException("Container " + container.getId() + " has not been created using Fabric");
        }
        ContainerProvider provider = getProvider(type);
        if (provider == null) {
            if (returnNull) {
                return null;
            }
            throw new UnsupportedOperationException("Container provider " + type + " not supported");
        }
        return provider;
    }

    @Override
    public CreateContainerMetadata[] createContainers(CreateContainerOptions options) {
        return createContainers(options, null);
    }

    @Override
    public CreateContainerMetadata[] createContainers(CreateContainerOptions options, CreationStateListener listener) {
        assertValid();
        try {
            final ContainerProvider provider = getProvider(options.getProviderType());
            if (provider == null) {
                throw new FabricException("Unable to find a container provider supporting '" + options.getProviderType() + "'");
            }

            String originalName = options.getName();
            if (originalName == null || originalName.length() == 0) {
                throw new FabricException("A name must be specified when creating containers");
            }

            if (listener == null) {
                listener = new NullCreationStateListener();
            }

            ObjectMapper mapper = new ObjectMapper();
            mapper.configure(DeserializationConfig.Feature.FAIL_ON_UNKNOWN_PROPERTIES, false);
            Map optionsMap = mapper.readValue(mapper.writeValueAsString(options), Map.class);
            String versionId = options.getVersion() != null ? options.getVersion() : getDataStore().getDefaultVersion();
            Set<String> profileIds = options.getProfiles();
            if (profileIds == null || profileIds.isEmpty()) {
                profileIds = new LinkedHashSet<String>();
                profileIds.add("default");
            }
            optionsMap.put("version", versionId);
            optionsMap.put("profiles", profileIds);
            optionsMap.put("number", 0);

            final List<CreateContainerMetadata> metadatas = new CopyOnWriteArrayList<CreateContainerMetadata>();
            int orgNumber = options.getNumber();
            int number = Math.max(orgNumber, 1);
            final CountDownLatch latch = new CountDownLatch(number);
            for (int i = 1; i <= number; i++) {
                String containerName;
                if (orgNumber >= 1) {
                    containerName = originalName + i;
                } else {
                    containerName = originalName;
                }
                optionsMap.put("name", containerName);
                Class cl = options.getClass().getClassLoader().loadClass(options.getClass().getName() + "$Builder");
                CreateContainerBasicOptions.Builder builder = (CreateContainerBasicOptions.Builder) mapper.readValue(mapper.writeValueAsString(optionsMap), cl);
                final CreateContainerOptions containerOptions = builder.build();
                final CreationStateListener containerListener = listener;
                new Thread("Creating container " + containerName) {
                    public void run() {
                        try {
                            getDataStore().createContainerConfig(containerOptions);
                            CreateContainerMetadata metadata = provider.create(containerOptions, containerListener);
                            if (metadata.isSuccess()) {
                                Container parent = containerOptions.getParent() != null ? getContainer(containerOptions.getParent()) : null;
                                //An ensemble server can be created without an existing ensemble.
                                //In this case container config will be created by the newly created container.
                                //TODO: We need to make sure that this entries are somehow added even to ensemble servers.
                                if (!containerOptions.isEnsembleServer()) {
                                    getDataStore().createContainerConfig(metadata);
                                }
                                ContainerImpl container = new ContainerImpl(parent, metadata.getContainerName(), FabricServiceImpl.this);
                                metadata.setContainer(container);
                                container.setMetadata(metadata);
                                LOGGER.info("The container " + metadata.getContainerName() + " has been successfully created");
                            } else {
                                LOGGER.info("The creation of the container " + metadata.getContainerName() + " has failed", metadata.getFailure());
                            }
                            metadatas.add(metadata);
                        } catch (Throwable t) {
                            CreateContainerBasicMetadata metadata = new CreateContainerBasicMetadata();
                            metadata.setCreateOptions(containerOptions);
                            metadata.setFailure(t);
                            metadatas.add(metadata);
                        } finally {
                            latch.countDown();
                        }
                    }
                }.start();
            }
            if (!latch.await(15, TimeUnit.MINUTES)) {
                throw new FabricException("Timeout waiting for container creation");
            }
            return metadatas.toArray(new CreateContainerMetadata[metadatas.size()]);
        } catch (Exception e) {
            LOGGER.error("Failed to create containers " + e, e);
            throw FabricException.launderThrowable(e);
        }
    }

    @Override
    public Set<Class<? extends CreateContainerBasicOptions>> getSupportedCreateContainerOptionTypes() {
        assertValid();
        Set<Class<? extends CreateContainerBasicOptions>> optionTypes = new HashSet<Class<? extends CreateContainerBasicOptions>>();
        for (Map.Entry<String, ContainerProvider> entry : providers.entrySet()) {
            optionTypes.add(entry.getValue().getOptionsType());
        }
        return optionTypes;
    }

    @Override
    public Set<Class<? extends CreateContainerBasicMetadata>> getSupportedCreateContainerMetadataTypes() {
        assertValid();
        Set<Class<? extends CreateContainerBasicMetadata>> metadataTypes = new HashSet<Class<? extends CreateContainerBasicMetadata>>();
        for (Map.Entry<String, ContainerProvider> entry : providers.entrySet()) {
            metadataTypes.add(entry.getValue().getMetadataType());
        }
        return metadataTypes;
    }

    @Override
    public ContainerProvider getProvider(final String scheme) {
        return providers.get(scheme);
    }

    // FIXME public access on the impl
    public Map<String, ContainerProvider> getProviders() {
        assertValid();
        return Collections.unmodifiableMap(providers);
    }

    @Override
    public URI getMavenRepoURI() {
        assertValid();
        URI uri = URI.create(getDefaultRepo());
        try {
            if (exists(curator.get(), ZkPath.MAVEN_PROXY.getPath("download")) != null) {
                List<String> children = getChildren(curator.get(), ZkPath.MAVEN_PROXY.getPath("download"));
                if (children != null && !children.isEmpty()) {
                    Collections.sort(children);
                }

                String mavenRepo = getSubstitutedPath(curator.get(), ZkPath.MAVEN_PROXY.getPath("download") + "/" + children.get(0));
                if (mavenRepo != null && !mavenRepo.endsWith("/")) {
                    mavenRepo += "/";
                }
                uri = new URI(mavenRepo);
            }
        } catch (Exception e) {
            //On exception just return uri.
        }
        return uri;
    }

    @Override
    public List<URI> getMavenRepoURIs() {
        assertValid();
        try {
            List<URI> uris = new ArrayList<URI>();
            if (exists(curator.get(), ZkPath.MAVEN_PROXY.getPath("download")) != null) {
                List<String> children = getChildren(curator.get(), ZkPath.MAVEN_PROXY.getPath("download"));
                if (children != null && !children.isEmpty()) {
                    Collections.sort(children);
                }
                if (children != null) {
                    for (String child : children) {
                        String mavenRepo = getSubstitutedPath(curator.get(), ZkPath.MAVEN_PROXY.getPath("download") + "/" + child);
                        if (mavenRepo != null && !mavenRepo.endsWith("/")) {
                            mavenRepo += "/";
                        }
                        uris.add(new URI(mavenRepo));
                    }
                }
            }
            return uris;
        } catch (Exception e) {
            throw FabricException.launderThrowable(e);
        }
    }

    @Override
    public URI getMavenRepoUploadURI() {
        assertValid();
        URI uri = URI.create(getDefaultRepo());
        try {
            if (exists(curator.get(), ZkPath.MAVEN_PROXY.getPath("upload")) != null) {
                List<String> children = getChildren(curator.get(), ZkPath.MAVEN_PROXY.getPath("upload"));
                if (children != null && !children.isEmpty()) {
                    Collections.sort(children);
                }

                String mavenRepo = getSubstitutedPath(curator.get(), ZkPath.MAVEN_PROXY.getPath("upload") + "/" + children.get(0));
                if (mavenRepo != null && !mavenRepo.endsWith("/")) {
                    mavenRepo += "/";
                }
                uri = new URI(mavenRepo);
            }
        } catch (Exception e) {
            //On exception just return uri.
        }
        return uri;
    }

    public String containerWebAppURL(String webAppId, String name) {
        assertValid();
        String answer = null;
        try {
            String versionsPath = ZkPath.WEBAPPS_CLUSTER.getPath(webAppId);
            if (exists(curator.get(), versionsPath) != null) {
                List<String> children = getChildren(curator.get(), versionsPath);
                if (children != null && !children.isEmpty()) {
                    for (String child : children) {
                        if (Strings.isNullOrEmpty(name)) {
                            // lets just use the first container we find
                            String parentPath = versionsPath + "/" + child;
                            List<String> grandChildren = getChildren(curator.get(), parentPath);
                            if (!grandChildren.isEmpty()) {
                                String containerPath = parentPath + "/" + grandChildren.get(0);
                                answer = getWebUrl(containerPath);
                                if (!Strings.isNullOrEmpty(answer)) {
                                    return answer;
                                }
                            }
                        } else {
                            String containerPath = versionsPath + "/" + child + "/" + name;
                            answer = getWebUrl(containerPath);
                            if (!Strings.isNullOrEmpty(answer)) {
                                return answer;
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.error("Failed to find container Jolokia URL " + e, e);
        }
        return answer;

    }

    private String getWebUrl(String containerPath) throws Exception {
        if (curator.get().checkExists().forPath(containerPath) != null) {
            byte[] bytes = ZkPath.loadURL(curator.get(), containerPath);
            String text = new String(bytes);
            // NOTE this is a bit naughty, we should probably be doing
            // Jackson parsing here; but we only need 1 String and
            // this avoids the jackson runtime dependency - its just a bit brittle
            // only finding http endpoints and all
            String prefix = "\"services\":[\"";
            int idx = text.indexOf(prefix);
            String answer = text;
            if (idx > 0) {
                int startIndex = idx + prefix.length();
                int endIdx = text.indexOf("\"]", startIndex);
                if (endIdx > 0) {
                    answer = text.substring(startIndex, endIdx);
                    if (answer.length() > 0) {
                        // lets expand any variables
                        answer = ZooKeeperUtils.getSubstitutedData(curator.get(), answer);
                        return answer;
                    }
                }
            }
        }
        return null;
    }

    // FIXME public access on the impl
    public void registerProvider(String scheme, ContainerProvider provider) {
        assertValid();
        providers.put(scheme, provider);
    }

    // FIXME public access on the impl
    public void registerProvider(ContainerProvider provider, Map<String, Object> properties) {
        assertValid();
        String scheme = (String) properties.get(Constants.PROTOCOL);
        registerProvider(scheme, provider);
    }

    // FIXME public access on the impl
    public void unregisterProvider(String scheme) {
        assertValid();
        providers.remove(scheme);
    }

    // FIXME public access on the impl
    public void unregisterProvider(ContainerProvider provider, Map<String, Object> properties) {
        assertValid();
        String scheme = (String) properties.get(Constants.PROTOCOL);
        unregisterProvider(scheme);
    }

    @Override
    public String getZookeeperUrl() {
        assertValid();
        return getZookeeperInfo("zookeeper.url");
    }

    @Override
    public String getZookeeperPassword() {
        assertValid();
        return getZookeeperInfo("zookeeper.password");
    }

    // FIXME public access on the impl
    public String getZookeeperInfo(String name) {
        assertValid();
        String zooKeeperUrl = null;
        //We are looking directly for at the zookeeper for the url, since container might not even be mananaged.
        //Also this is required for the integration with the IDE.
        try {
            if (curator.get().getZookeeperClient().isConnected()) {
                Version defaultVersion = getDefaultVersion();
                if (defaultVersion != null) {
                    Profile profile = defaultVersion.getProfile("default");
                    if (profile != null) {
                        Map<String, String> zookeeperConfig =  profile.getConfiguration("org.fusesource.fabric.zookeeper");
                        if (zookeeperConfig != null) {
                            zooKeeperUrl = getSubstitutedData(curator.get(), zookeeperConfig.get(name));
                        }
                    }
                }
            }
        } catch (Exception e) {
            //Ignore it.
        }

        if (zooKeeperUrl == null) {
            try {
                Configuration config = configAdmin.get().getConfiguration("org.fusesource.fabric.zookeeper", null);
                zooKeeperUrl = (String) config.getProperties().get(name);
            } catch (Exception e) {
                //Ignore it.
            }
        }
        return zooKeeperUrl;
    }

    @Override
    public Version getDefaultVersion() {
        assertValid();
        return new VersionImpl(getDataStore().getDefaultVersion(), this);
    }

    @Override
    public void setDefaultVersion(Version version) {
        assertValid();
        setDefaultVersion(version.getId());
    }

    public void setDefaultVersion(String versionId) {
        assertValid();
        getDataStore().setDefaultVersion(versionId);
    }

    @Override
    public Version createVersion(String version) {
        assertValid();
        getDataStore().createVersion(version);
        return new VersionImpl(version, this);
    }

    @Override
    public Version createVersion(Version parent, String toVersion) {
        assertValid();
        return createVersion(parent.getId(), toVersion);
    }

    // FIXME public access on the impl
    public Version createVersion(String parentVersionId, String toVersion) {
        assertValid();
        getDataStore().createVersion(parentVersionId, toVersion);
        return new VersionImpl(toVersion, this);
    }

    // FIXME public access on the impl
    public void deleteVersion(String version) {
        assertValid();
        getVersion(version).delete();
    }

    @Override
    public Version[] getVersions() {
        assertValid();
        List<Version> versions = new ArrayList<Version>();
        List<String> children = getDataStore().getVersions();
        for (String child : children) {
            versions.add(new VersionImpl(child, this));
        }
        Collections.sort(versions);
        return versions.toArray(new Version[versions.size()]);
    }

    @Override
    public Version getVersion(String name) {
        assertValid();
        if (getDataStore().hasVersion(name)) {
            return new VersionImpl(name, this);
        }
        throw new FabricException("Version '" + name + "' does not exist");
    }

    @Override
    public Profile[] getProfiles(String version) {
        assertValid();
        return getVersion(version).getProfiles();
    }

    @Override
    public Profile getProfile(String version, String name) {
        assertValid();
        return getVersion(version).getProfile(name);
    }

    @Override
    public Profile createProfile(String version, String name) {
        assertValid();
        getDataStore().createProfile(version, name);
        return new ProfileImpl(name, version, this);
    }

    @Override
    public void deleteProfile(Profile profile) {
        assertValid();
        deleteProfile(profile.getVersion(), profile.getId());
    }

    private void deleteProfile(String versionId, String profileId) {
        getDataStore().deleteProfile(versionId, profileId);
    }

    @Override
    public void setRequirements(FabricRequirements requirements) throws IOException {
        assertValid();
        getDataStore().setRequirements(requirements);
    }

    @Override
    public FabricRequirements getRequirements() {
        assertValid();
        FabricRequirements requirements = getDataStore().getRequirements();
        Version defaultVersion = getDefaultVersion();
        if (defaultVersion != null) {
            requirements.setVersion(defaultVersion.getId());
        }
        return requirements;
    }

    @Override
    public FabricStatus getFabricStatus() {
        assertValid();
        return new FabricStatus(this);
    }

    @Override
    public PatchService getPatchService() {
        assertValid();
        return new PatchServiceImpl(this, configAdmin.get());
    }

    @Override
    public String getDefaultJvmOptions() {
        assertValid();
        return getDataStore().getDefaultJvmOptions();
    }

    @Override
    public void setDefaultJvmOptions(String jvmOptions) {
        assertValid();
        getDataStore().setDefaultJvmOptions(jvmOptions);
    }

    @Override
    public String getConfigurationValue(String versionId, String profileId, String pid, String key) {
        assertValid();
        Version v = getVersion(versionId);
        if (v == null)
            throw new FabricException("No version found: " + versionId);
        Profile pr = v.getProfile(profileId);
        if (pr == null)
            throw new FabricException("No profile found: " + profileId);
        Map<String, byte[]> configs = pr.getFileConfigurations();

        byte[] b = configs.get(pid);

        Properties p = null;

        try {
            if (b != null) {
                p = DataStoreHelpers.toProperties(b);
            } else {
                p = new Properties();
            }
        } catch (Throwable t) {
            throw new FabricException(t);
        }

        return p.getProperty(key);
    }

    @Override
    public void setConfigurationValue(String versionId, String profileId, String pid, String key, String value) {
        assertValid();
        Version v = getVersion(versionId);
        if (v == null)
            throw new FabricException("No version found: " + versionId);
        Profile pr = v.getProfile(profileId);
        if (pr == null)
            throw new FabricException("No profile found: " + profileId);
        Map<String, byte[]> configs = pr.getFileConfigurations();

        byte[] b = configs.get(pid);

        Properties p = null;

        try {
            if (b != null) {
                p = DataStoreHelpers.toProperties(b);
            } else {
                p = new Properties();
            }
            p.setProperty(key, value);
            b = DataStoreHelpers.toBytes(p);
            configs.put(pid, b);
            pr.setFileConfigurations(configs);
        } catch (Throwable t) {
            throw new FabricException(t);
        }
    }

    @Override
    public boolean scaleProfile(String profile, int numberOfInstances) throws IOException {
        if (numberOfInstances == 0) {
            throw new IllegalArgumentException("numberOfInstances should be greater or less than zero");
        }
        FabricRequirements requirements = getRequirements();
        ProfileRequirements profileRequirements = requirements.getOrCreateProfileRequirement(profile);
        Integer minimumInstances = profileRequirements.getMinimumInstances();
        List<Container> containers = Containers.containersForProfile(getContainers(), profile);
        int containerCount = containers.size();
        int newCount = containerCount + numberOfInstances;
        if (newCount < 0) {
            newCount = 0;
        }
        boolean update = minimumInstances == null || newCount != minimumInstances;
        if (update) {
            profileRequirements.setMinimumInstances(newCount);
            setRequirements(requirements);
        }
        return update;
    }

    @Override
    public ContainerAutoScaler createContainerAutoScaler() {
        Collection<ContainerProvider> providerCollection = getProviders().values();
        for (ContainerProvider containerProvider : providerCollection) {
            if (containerProvider instanceof ContainerAutoScalerFactory) {
                ContainerAutoScalerFactory provider = (ContainerAutoScalerFactory) containerProvider;
                ContainerAutoScaler answer = provider.createAutoScaler();
                if (answer != null) {
                    return answer;
                }
            }
        }
        return null;
    }

    void bindConfigAdmin(ConfigurationAdmin service) {
        this.configAdmin.bind(service);
    }

    void unbindConfigAdmin(ConfigurationAdmin service) {
        this.configAdmin.unbind(service);
    }

    void bindCurator(CuratorFramework curator) {
        this.curator.bind(curator);
    }

    void unbindCurator(CuratorFramework curator) {
        this.curator.unbind(curator);
    }

    void bindDataStore(DataStore dataStore) {
        this.dataStore.bind(dataStore);
    }

    void unbindDataStore(DataStore dataStore) {
        this.dataStore.unbind(dataStore);
    }

    void bindPortService(PortService portService) {
        this.portService.bind(portService);
    }

    void unbindPortService(PortService portService) {
        this.portService.unbind(portService);
    }

    void bindProvider(ContainerProvider provider) {
        providers.put(provider.getScheme(), provider);
    }

    void unbindProvider(ContainerProvider provider) {
        providers.remove(provider.getScheme());
    }
}
