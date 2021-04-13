package me.qoomon.maven.gitversioning;

import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.google.inject.Key;
import com.google.inject.OutOfScopeException;
import de.pdark.decentxml.Document;
import de.pdark.decentxml.Element;
import me.qoomon.gitversioning.commons.GitHeadSituation;
import me.qoomon.gitversioning.commons.GitUtil;
import me.qoomon.maven.gitversioning.Configuration.PropertyDescription;
import me.qoomon.maven.gitversioning.Configuration.VersionDescription;
import org.apache.maven.artifact.versioning.DefaultArtifactVersion;
import org.apache.maven.building.Source;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.*;
import org.apache.maven.model.building.DefaultModelProcessor;
import org.apache.maven.model.building.ModelProcessor;
import org.apache.maven.session.scope.internal.SessionScope;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.slf4j.Logger;

import javax.enterprise.inject.Typed;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.regex.Pattern;

import static java.lang.Boolean.parseBoolean;
import static java.lang.Math.*;
import static java.time.format.DateTimeFormatter.ISO_INSTANT;
import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static java.util.Comparator.comparing;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;
import static me.qoomon.gitversioning.commons.GitRefType.*;
import static me.qoomon.gitversioning.commons.StringUtil.substituteText;
import static me.qoomon.gitversioning.commons.StringUtil.valueGroupMap;
import static me.qoomon.maven.gitversioning.BuildProperties.projectArtifactId;
import static me.qoomon.maven.gitversioning.GitVersioningMojo.GOAL;
import static me.qoomon.maven.gitversioning.GitVersioningMojo.asPlugin;
import static me.qoomon.maven.gitversioning.MavenUtil.*;
import static org.apache.maven.shared.utils.StringUtils.leftPad;
import static org.apache.maven.shared.utils.StringUtils.repeat;
import static org.apache.maven.shared.utils.logging.MessageUtils.buffer;
import static org.slf4j.LoggerFactory.getLogger;

// TODO add option to throw an error if git has non clean state

/**
 * Replacement for {@link ModelProcessor} to adapt versions.
 */
@Named("core-default")
@Singleton
@Typed(ModelProcessor.class)
@SuppressWarnings("CdiInjectionPointsInspection")
public class GitVersioningModelProcessor extends DefaultModelProcessor {

    private static final String OPTION_NAME_GIT_TAG = "git.tag";
    private static final String OPTION_NAME_GIT_BRANCH = "git.branch";
    private static final String OPTION_NAME_DISABLE = "versioning.disable";
    private static final String OPTION_UPDATE_POM = "versioning.updatePom";
    private static final String OPTION_PREFER_TAGS = "versioning.preferTags";

    private static final String DEFAULT_BRANCH_VERSION_FORMAT = "${branch}-SNAPSHOT";
    private static final String DEFAULT_TAG_VERSION_FORMAT = "${tag}";
    private static final String DEFAULT_COMMIT_VERSION_FORMAT = "${commit}";

    static final String GIT_VERSIONING_POM_NAME = ".git-versioned-pom.xml";

    final private Logger logger = getLogger(GitVersioningModelProcessor.class);

    @Inject
    private SessionScope sessionScope;

    private boolean initialized = false;
    // --- following fields will be initialized by init() method -------------------------------------------------------

    private MavenSession mavenSession; // can't be injected, cause it's not available before model read
    private File mvnDirectory;
    private GitHeadSituation gitHeadSituation;

    private boolean disabled = false;
    private boolean updatePomOption = false;
    private GitVersionDetails gitVersionDetails;
    private Map<String, PropertyDescription> gitVersioningPropertyDescriptionMap;
    private Map<String, String> globalFormatPlaceholderMap;
    private Map<String, String> gitProjectProperties;
    private Set<GAV> relatedProjects;


    // ---- other fields -----------------------------------------------------------------------------------------------

    private final Set<File> projectModules = new HashSet<>();
    private final Map<File, Model> sessionModelCache = new HashMap<>();


    @Override
    public Model read(File input, Map<String, ?> options) throws IOException {
        final Model projectModel = super.read(input, options);
        return processModel(projectModel, options);
    }

    @Override
    public Model read(Reader input, Map<String, ?> options) throws IOException {
        final Model projectModel = super.read(input, options);
        return processModel(projectModel, options);
    }

    @Override
    public Model read(InputStream input, Map<String, ?> options) throws IOException {
        final Model projectModel = super.read(input, options);
        return processModel(projectModel, options);
    }


    private void init(Model projectModel) throws IOException {
        logger.info("");
        logger.info(extensionLogHeader(BuildProperties.projectGAV()));

        // check if session is available
        try {
            mavenSession = sessionScope.scope(Key.get(MavenSession.class), null).get();
        } catch (OutOfScopeException ex) {
            logger.warn("skip - no maven session present");
            disabled = true;
            return;
        }

        File executionRootDirectory = new File(mavenSession.getRequest().getBaseDirectory());
        logger.debug("execution root directory: " + executionRootDirectory);

        mvnDirectory = findMvnDirectory(executionRootDirectory);
        logger.debug(".mvn directory: " + mvnDirectory);

        File configFile = new File(mvnDirectory, projectArtifactId() + ".xml");
        logger.debug("read config from " + configFile);
        Configuration config = readConfig(configFile);

        // check if extension is disabled by command option
        String commandOptionDisable = getCommandOption(OPTION_NAME_DISABLE);
        if (commandOptionDisable != null) {
            disabled = parseBoolean(commandOptionDisable);
            if (disabled) {
                logger.info("skip - versioning is disabled by command option");
                return;
            }
        } else {
            // check if extension is disabled by config option
            disabled = config.disable != null && config.disable;
            if (disabled) {
                logger.info("skip - versioning is disabled by config option");
                return;
            }
        }

        // determine git situation
        gitHeadSituation = getGitHeadSituation(executionRootDirectory);
        if (gitHeadSituation == null) {
            logger.warn("skip - project is not part of a git repository");
            disabled = true;
            return;
        }
        logger.debug(buffer().strong("git situation:").toString());
        logger.debug("  root directory: " + gitHeadSituation.getRootDirectory());
        logger.debug("  head commit: " + gitHeadSituation.getHeadCommit());
        logger.debug("  head commit timestamp: " + gitHeadSituation.getHeadCommitTimestamp());
        logger.debug("  head branch: " + gitHeadSituation.getHeadBranch());
        logger.debug("  head tags: " + gitHeadSituation.getHeadTags());

        // determine git version details
        boolean preferTagsOption = getPreferTagsOption(config);
        logger.debug(buffer().strong("option:").toString() + " prefer tags: " + preferTagsOption);
        gitVersionDetails = getGitVersionDetails(gitHeadSituation, config, preferTagsOption);
        logger.info("git ref: " + buffer().strong(gitVersionDetails.getRefName())
                + " (" + gitVersionDetails.getRefType().name().toLowerCase() + ")");
        gitVersioningPropertyDescriptionMap = gitVersionDetails.getConfig().property.stream()
                .collect(toMap(property -> property.name, property -> property));

        updatePomOption = getUpdatePomOption(config, gitVersionDetails.getConfig());
        logger.debug(buffer().strong("option:").toString() + " update pom: " + updatePomOption);

        // determine related projects
        relatedProjects = determineRelatedProjects(projectModel);
        logger.debug(buffer().strong("related projects:").toString());
        relatedProjects.forEach(gav -> logger.debug("  " + gav));

        // add session root project as initial module
        projectModules.add(projectModel.getPomFile());

        globalFormatPlaceholderMap = generateGlobalFormatPlaceholderMap(gitHeadSituation, gitVersionDetails, mavenSession);
        gitProjectProperties = generateGitProjectProperties(gitHeadSituation, gitVersionDetails);

        logger.info("");
    }

    // ---- model processing -------------------------------------------------------------------------------------------

    public Model processModel(Model projectModel, Map<String, ?> options) throws IOException {
        // set model pom file
        final Source pomSource = (Source) options.get(ModelProcessor.SOURCE);
        if (pomSource != null) {
            projectModel.setPomFile(new File(pomSource.getLocation()));
        } else {
            logger.debug("skip model - no project model pom file");
            return projectModel;
        }

        if (!initialized) {
            init(projectModel);
            initialized = true;
        }

        if (disabled) {
            return projectModel;
        }

        File canonicalProjectPomFile = projectModel.getPomFile().getCanonicalFile();

        if (!projectModules.contains(canonicalProjectPomFile)) {
            if (logger.isTraceEnabled()) {
                logger.trace("skip model - non project module - " + projectModel.getPomFile());
            }
            return projectModel;
        }

        GAV projectGAV = GAV.of(projectModel);
        if (projectGAV.getVersion() == null) {
            logger.debug("skip model - can not determine project version - " + projectModel.getPomFile());
            return projectModel;
        }

        // return cached calculated project model if present
        Model cachedProjectModel = sessionModelCache.get(canonicalProjectPomFile);
        if (cachedProjectModel != null) {
            return cachedProjectModel;
        }

        // add current project model to session project models
        sessionModelCache.put(canonicalProjectPomFile, projectModel);

        // log project header
        logger.info(projectLogHeader(projectGAV));

        updateModel(projectModel);

        addGitProperties(projectModel);

        File gitVersionedPomFile = writePomFile(projectModel);
        if (updatePomOption) {
            logger.debug("updating original POM file");
            Files.copy(
                    gitVersionedPomFile.toPath(),
                    projectModel.getPomFile().toPath(),
                    StandardCopyOption.REPLACE_EXISTING);
        }

        // git versioned pom can't be set as model pom right away, file
        // cause it will break plugins, that trying to update original pom file
        //   e.g. mvn versions:set -DnewVersion=1.0.0
        // That's why we need to add a build plugin that sets project pom file to git versioned pom file
        addBuildPlugin(projectModel);

        // add potential project modules
        for (File modulePomFile : getProjectModules(projectModel)) {
            projectModules.add(modulePomFile.getCanonicalFile());
        }

        logger.info("");
        return projectModel;
    }

    private void updateModel(Model projectModel) {
        GAV originalProjectGAV = GAV.of(projectModel);

        updateVersion(projectModel.getParent());
        updateVersion(projectModel);
        logger.info("project version: " + GAV.of(projectModel).getVersion());

        updatePropertyValues(projectModel, originalProjectGAV);
        updateDependencyVersions(projectModel);
        updatePluginVersions(projectModel);

        // profile section
        updateProfiles(projectModel.getProfiles(), originalProjectGAV);
    }

    private void updateProfiles(List<Profile> profiles, GAV originalProjectGAV) {
        // profile section
        if (!profiles.isEmpty()) {
            for (Profile profile : profiles) {
                updatePropertyValues(profile, originalProjectGAV);
                updateDependencyVersions(profile);
                updatePluginVersions(profile);
            }
        }
    }

    private void updateVersion(Parent parent) {
        if (parent != null) {
            GAV parentGAV = GAV.of(parent);
            if (relatedProjects.contains(parentGAV)) {
                String gitVersion = getGitVersion(parentGAV);
                logger.debug("set parent version to " + gitVersion + " (" + parentGAV + ")");
                parent.setVersion(getGitVersion(parentGAV));
            }
        }
    }

    private void updateVersion(Model projectModel) {
        if (projectModel.getVersion() != null) {
            GAV projectGAV = GAV.of(projectModel);
            String gitVersion = getGitVersion(projectGAV);
            logger.debug("set version to " + gitVersion);
            projectModel.setVersion(gitVersion);
        }
    }

    private void updatePropertyValues(ModelBase model, GAV originalProjectGAV) {
        boolean logHeader = true;
        // properties section
        for (Map.Entry<Object, Object> entry : model.getProperties().entrySet()) {
            String gitPropertyValue = getGitProjectPropertyValue(originalProjectGAV,
                    (String) entry.getKey(), (String) entry.getValue());
            if (!gitPropertyValue.equals(entry.getValue())) {
                if (logHeader) {
                    logger.info(sectionLogHeader("properties", model));
                    logHeader = false;
                }
                logger.info(entry.getKey() + ": " + gitPropertyValue);
                model.addProperty((String) entry.getKey(), gitPropertyValue);
            }
        }
    }

    private void updatePluginVersions(ModelBase model) {
        BuildBase build = getBuild(model);
        if (build == null) {
            return;
        }
        // plugins section
        {
            List<Plugin> relatedPlugins = filterRelatedPlugins(build.getPlugins());
            if (!relatedPlugins.isEmpty()) {
                logger.debug(sectionLogHeader("plugins", model));
                for (Plugin plugin : relatedPlugins) {
                    updateVersion(plugin);
                }
            }
        }

        // plugin management section
        PluginManagement pluginManagement = build.getPluginManagement();
        if (pluginManagement != null) {
            List<Plugin> relatedPlugins = filterRelatedPlugins(pluginManagement.getPlugins());
            if (!relatedPlugins.isEmpty()) {
                logger.debug(buffer().strong("plugin management:").toString());
                for (Plugin plugin : relatedPlugins) {
                    updateVersion(plugin);
                }
            }
        }

        // reporting section
        Reporting reporting = model.getReporting();
        if (reporting != null) {
            List<ReportPlugin> relatedPlugins = filterRelatedReportPlugins(reporting.getPlugins());
            if (!relatedPlugins.isEmpty()) {
                logger.debug(buffer().strong("reporting plugins:").toString());
                for (ReportPlugin plugin : relatedPlugins) {
                    updateVersion(plugin);
                }
            }
        }
    }

    private void updateVersion(Plugin plugin) {
        if (plugin.getVersion() != null) {
            GAV pluginGAV = GAV.of(plugin);
            String gitVersion = getGitVersion(pluginGAV);
            logger.debug(pluginGAV.getProjectId() + ": set version to " + gitVersion);
            plugin.setVersion(gitVersion);
        }
    }

    private void updateVersion(ReportPlugin plugin) {
        if (plugin.getVersion() != null) {
            GAV pluginGAV = GAV.of(plugin);
            String gitVersion = getGitVersion(pluginGAV);
            logger.debug(pluginGAV.getProjectId() + ": set version to " + gitVersion);
            plugin.setVersion(gitVersion);
        }
    }

    private List<Plugin> filterRelatedPlugins(List<Plugin> plugins) {
        return plugins.stream()
                .filter(it -> relatedProjects.contains(GAV.of(it)))
                .collect(toList());
    }

    private List<ReportPlugin> filterRelatedReportPlugins(List<ReportPlugin> plugins) {
        return plugins.stream()
                .filter(it -> relatedProjects.contains(GAV.of(it)))
                .collect(toList());
    }

    private void updateDependencyVersions(ModelBase model) {
        // dependencies section
        {
            List<Dependency> relatedDependencies = filterRelatedDependencies(model.getDependencies());
            if (!relatedDependencies.isEmpty()) {
                logger.debug(sectionLogHeader("dependencies", model));
                for (Dependency dependency : relatedDependencies) {
                    updateVersion(dependency);
                }
            }
        }
        // dependency management section
        DependencyManagement dependencyManagement = model.getDependencyManagement();
        if (dependencyManagement != null) {
            List<Dependency> relatedDependencies = filterRelatedDependencies(dependencyManagement.getDependencies());
            if (!relatedDependencies.isEmpty()) {
                logger.debug(buffer().strong("dependency management:").toString());
                for (Dependency dependency : relatedDependencies) {
                    updateVersion(dependency);
                }
            }
        }
    }

    private void updateVersion(Dependency dependency) {
        if (dependency.getVersion() != null) {
            GAV dependencyGAV = GAV.of(dependency);
            String gitVersion = getGitVersion(dependencyGAV);
            logger.debug(dependencyGAV.getProjectId() + ": set version to " + gitVersion);
            dependency.setVersion(gitVersion);
        }
    }

    public List<Dependency> filterRelatedDependencies(List<Dependency> dependencies) {
        return dependencies.stream()
                .filter(it -> relatedProjects.contains(GAV.of(it)))
                .collect(toList());
    }

    private void addGitProperties(Model projectModel) {
        gitProjectProperties.forEach(projectModel::addProperty);
    }

    private void addBuildPlugin(Model projectModel) {
        logger.debug("add version build plugin");

        Plugin plugin = asPlugin();

        PluginExecution execution = new PluginExecution();
        execution.setId(GOAL);
        execution.getGoals().add(GOAL);

        plugin.getExecutions().add(execution);

        if (projectModel.getBuild() == null) {
            projectModel.setBuild(new Build());
        }
        projectModel.getBuild().getPlugins().add(plugin);
    }


    // ---- versioning -------------------------------------------------------------------------------------------------

    private GitHeadSituation getGitHeadSituation(File executionRootDirectory) throws IOException {
        Repository repository = new FileRepositoryBuilder().findGitDir(executionRootDirectory).build();
        if (repository.getWorkTree() == null) {
            return null;
        }

        GitHeadSituation gitHeadSituation = new GitHeadSituation(repository, Pattern.compile(".*")); // TODO

        String providedTag = getCommandOption(OPTION_NAME_GIT_TAG);
        if (providedTag != null) {
            logger.debug("set git head tag by command option: " + providedTag);
            gitHeadSituation.setBranch(null);
            gitHeadSituation.setTags(providedTag.isEmpty() ? emptyList() : singletonList(providedTag));
        }
        String providedBranch = getCommandOption(OPTION_NAME_GIT_BRANCH);
        if (providedBranch != null) {
            logger.debug("set git head branch by command option: " + providedBranch);
            gitHeadSituation = gitHeadSituation.Builder.of(gitHeadSituation)
                    .setHeadBranch(providedBranch)
                    .build();
        }

        return gitHeadSituation;
    }

    private static GitVersionDetails getGitVersionDetails(GitHeadSituation GitHeadSituation, Configuration config, boolean preferTags) {
        String headCommit = GitHeadSituation.getHeadCommit();

        // detached tag
        if (GitHeadSituation.isDetached() || preferTags) {
            // sort tags by maven version logic
            List<String> sortedHeadTags = GitHeadSituation.getHeadTags().stream()
                    .sorted(comparing(DefaultArtifactVersion::new)).collect(toList());
            for (VersionDescription tagConfig : config.tag) {
                for (String headTag : sortedHeadTags) {
                    if (tagConfig.pattern == null || headTag.matches(tagConfig.pattern)) {
                        return new GitVersionDetails(headCommit, TAG, headTag, tagConfig);
                    }
                }
            }
        }

        // detached commit
        if (GitHeadSituation.isDetached()) {
            if (config.commit != null) {
                if (config.commit.pattern == null || headCommit.matches(config.commit.pattern)) {
                    return new GitVersionDetails(headCommit, COMMIT, headCommit, config.commit);
                }
            }

            // default config for detached head commit
            return new GitVersionDetails(headCommit, COMMIT, headCommit, new VersionDescription() {{
                versionFormat = DEFAULT_COMMIT_VERSION_FORMAT;
            }});
        }

        // branch
        {
            String headBranch = GitHeadSituation.getHeadBranch();
            for (VersionDescription branchConfig : config.branch) {
                if (branchConfig.pattern == null || headBranch.matches(branchConfig.pattern)) {
                    return new GitVersionDetails(headCommit, BRANCH, headBranch, branchConfig);
                }
            }

            // default config for branch
            return new GitVersionDetails(headCommit, BRANCH, headBranch, new VersionDescription() {{
                versionFormat = DEFAULT_BRANCH_VERSION_FORMAT;
            }});
        }
    }

    private String getGitVersion(GAV originalProjectGAV) {
        final Map<String, String> placeholderMap = generateFormatPlaceholderMap(originalProjectGAV);
        return substituteText(gitVersionDetails.getConfig().versionFormat, placeholderMap)
                // replace invalid version characters
                .replace("/", "-");
    }

    private String getGitProjectPropertyValue(GAV originalProjectGAV, String key, String originalValue) {
        PropertyDescription propertyConfig = gitVersioningPropertyDescriptionMap.get(key);
        if (propertyConfig == null) {
            return originalValue;
        }
        final Map<String, String> placeholderMap = generateFormatPlaceholderMap(originalProjectGAV);
        placeholderMap.put("value", originalValue);
        return substituteText(propertyConfig.valueFormat, placeholderMap);
    }

    private Map<String, String> generateFormatPlaceholderMap(GAV originalProjectGAV) {
        final Map<String, String> placeholderMap = new HashMap<>();
        placeholderMap.putAll(globalFormatPlaceholderMap);
        placeholderMap.putAll(generateFormatPlaceholderMapFromVersion(originalProjectGAV));
        return placeholderMap;
    }

    private static Map<String, String> generateGlobalFormatPlaceholderMap(GitHeadSituation GitHeadSituation, GitVersionDetails gitVersionDetails, MavenSession mavenSession) {
        final Map<String, String> placeholderMap = new HashMap<>();

        String headCommit = GitHeadSituation.getHeadCommit();
        placeholderMap.put("commit", headCommit);
        placeholderMap.put("commit.short", headCommit.substring(0, 7));

        ZonedDateTime headCommitDateTime = GitHeadSituation.getHeadCommitDateTime();
        placeholderMap.put("commit.timestamp", String.valueOf(headCommitDateTime.toEpochSecond()));
        placeholderMap.put("commit.timestamp.year", String.valueOf(headCommitDateTime.getYear()));
        placeholderMap.put("commit.timestamp.month", leftPad(String.valueOf(headCommitDateTime.getMonthValue()), 2, "0"));
        placeholderMap.put("commit.timestamp.day", leftPad(String.valueOf(headCommitDateTime.getDayOfMonth()), 2, "0"));
        placeholderMap.put("commit.timestamp.hour", leftPad(String.valueOf(headCommitDateTime.getHour()), 2, "0"));
        placeholderMap.put("commit.timestamp.minute", leftPad(String.valueOf(headCommitDateTime.getMinute()), 2, "0"));
        placeholderMap.put("commit.timestamp.second", leftPad(String.valueOf(headCommitDateTime.getSecond()), 2, "0"));
        placeholderMap.put("commit.timestamp.datetime", headCommitDateTime.toEpochSecond() > 0
                ? headCommitDateTime.format(DateTimeFormatter.ofPattern("yyyyMMdd.HHmmss")) : "00000000.000000");

        String refTypeName = gitVersionDetails.getRefType().name().toLowerCase();
        String refName = gitVersionDetails.getRefName();
        String refNameSlug = slugify(refName);
        placeholderMap.put("ref", refName);
        placeholderMap.put("ref.slug", refNameSlug);
        placeholderMap.put(refTypeName, refName);
        placeholderMap.put(refTypeName + ".slug", refNameSlug);
        String refPattern = gitVersionDetails.getConfig().pattern;
        if (refPattern != null) {
            Map<String, String> refNameValueGroupMap = valueGroupMap(refName, refPattern);
            placeholderMap.putAll(refNameValueGroupMap);
            placeholderMap.putAll(refNameValueGroupMap.entrySet().stream()
                    .collect(toMap(entry -> entry.getKey() + ".slug", entry -> slugify(entry.getValue()))));
        }

        placeholderMap.put("dirty", !GitHeadSituation.isClean() ? "-DIRTY" : "");
        placeholderMap.put("dirty.snapshot", !GitHeadSituation.isClean() ? "-SNAPSHOT" : "");

        // command parameters e.g. mvn -Dfoo=123 will be available as ${foo}
        mavenSession.getUserProperties().forEach((key, value) -> placeholderMap.put((String) key, (String) value));

        // environment variables e.g. BUILD_NUMBER=123 will be available as ${env.BUILD_NUMBER}
        System.getenv().forEach((key, value) -> placeholderMap.put("env." + key, value));

        return placeholderMap;
    }

    private static Map<String, String> generateFormatPlaceholderMapFromVersion(GAV originalProjectGAV) {
        Map<String, String> placeholderMap = new HashMap<>();
        String originalProjectVersion = originalProjectGAV.getVersion();
        placeholderMap.put("version", originalProjectVersion);
        placeholderMap.put("version.release", originalProjectVersion.replaceFirst("-SNAPSHOT$", ""));
        return placeholderMap;
    }

    private static Map<String, String> generateGitProjectProperties(GitHeadSituation GitHeadSituation, GitVersionDetails gitVersionDetails) {
        Map<String, String> properties = new HashMap<>();

        properties.put("git.commit", gitVersionDetails.getCommit());

        ZonedDateTime headCommitDateTime = GitHeadSituation.getHeadCommitDateTime();
        properties.put("git.commit.timestamp", String.valueOf(headCommitDateTime.toEpochSecond()));
        properties.put("git.commit.timestamp.datetime", headCommitDateTime.toEpochSecond() > 0
                ? headCommitDateTime.format(ISO_INSTANT) : "0000-00-00T00:00:00Z");

        String refTypeName = gitVersionDetails.getRefType().name().toLowerCase();
        String refName = gitVersionDetails.getRefName();
        String refNameSlug = slugify(refName);
        properties.put("git.ref", refName);
        properties.put("git.ref.slug", refNameSlug);
        properties.put("git." + refTypeName, refName);
        properties.put("git." + refTypeName + ".slug", refNameSlug);

        properties.put("git.dirty", Boolean.toString(!GitHeadSituation.isClean()));

        return properties;
    }


    // ---- configuration -------------------------------------------------------------------------------------------------

    private static File findMvnDirectory(File baseDirectory) throws IOException {
        File searchDirectory = baseDirectory;
        while (searchDirectory != null) {
            File mvnDir = new File(searchDirectory, ".mvn");
            if (mvnDir.exists()) {
                return mvnDir;
            }
            searchDirectory = searchDirectory.getParentFile();
        }

        throw new FileNotFoundException("Can not find .mvn directory in hierarchy of " + baseDirectory);
    }

    private static Configuration readConfig(File configFile) throws IOException {
        Configuration config = new XmlMapper().readValue(configFile, Configuration.class);

        for (VersionDescription versionDescription : config.branch) {
            if (versionDescription.versionFormat == null) {
                versionDescription.versionFormat = DEFAULT_BRANCH_VERSION_FORMAT;
            }
        }
        for (VersionDescription versionDescription : config.tag) {
            if (versionDescription.versionFormat == null) {
                versionDescription.versionFormat = DEFAULT_TAG_VERSION_FORMAT;
            }
        }
        if (config.commit != null) {
            if (config.commit.versionFormat == null) {
                config.commit.versionFormat = DEFAULT_COMMIT_VERSION_FORMAT;
            }
        }

        return config;
    }

    private String getCommandOption(final String name) {
        String value = mavenSession.getUserProperties().getProperty(name);
        if (value == null) {
            String plainName = name.replaceFirst("^versioning\\.", "");
            String environmentVariableName = "VERSIONING_"
                    + String.join("_", plainName.split("(?=\\p{Lu})"))
                    .replaceAll("\\.", "_")
                    .toUpperCase();
            value = System.getenv(environmentVariableName);
        }
        if (value == null) {
            value = System.getProperty(name);
        }
        return value;
    }

    private boolean getPreferTagsOption(final Configuration config) {
        final String preferTagsCommandOption = getCommandOption(OPTION_PREFER_TAGS);
        if (preferTagsCommandOption != null) {
            return parseBoolean(preferTagsCommandOption);
        }

        if (config.preferTags != null) {
            return config.preferTags;
        }

        return false;
    }

    private boolean getUpdatePomOption(final Configuration config, final VersionDescription gitRefConfig) {
        final String updatePomCommandOption = getCommandOption(OPTION_UPDATE_POM);
        if (updatePomCommandOption != null) {
            return parseBoolean(updatePomCommandOption);
        }

        if (gitRefConfig.updatePom != null) {
            return gitRefConfig.updatePom;
        }

        if (config.updatePom != null) {
            return config.updatePom;
        }

        return false;
    }


    // ---- determine related projects ---------------------------------------------------------------------------------

    private Set<GAV> determineRelatedProjects(Model projectModel) throws IOException {
        HashSet<GAV> relatedProjects = new HashSet<>();
        determineRelatedProjects(projectModel, relatedProjects);
        return relatedProjects;
    }

    private void determineRelatedProjects(Model projectModel, Set<GAV> relatedProjects) throws IOException {
        GAV projectGAV = GAV.of(projectModel);
        if (relatedProjects.contains(projectGAV)) {
            return;
        }

        // add self
        relatedProjects.add(projectGAV);

        // check for related parent project by parent tag
        if (projectModel.getParent() != null) {
            GAV parentGAV = GAV.of(projectModel.getParent());
            File parentProjectPomFile = getParentProjectPomFile(projectModel);
            if (isRelatedPom(parentProjectPomFile)) {
                Model parentProjectModel = readModel(parentProjectPomFile);
                GAV parentProjectGAV = GAV.of(parentProjectModel);
                if (parentProjectGAV.equals(parentGAV)) {
                    determineRelatedProjects(parentProjectModel, relatedProjects);
                }
            }
        }

        // check for related parent project within parent directory
        Model parentProjectModel = searchParentProjectInParentDirectory(projectModel);
        if (parentProjectModel != null) {
            determineRelatedProjects(parentProjectModel, relatedProjects);
        }

        //  process modules
        for (File modulePomFile : getProjectModules(projectModel)) {
            Model moduleProjectModel = readModel(modulePomFile);
            determineRelatedProjects(moduleProjectModel, relatedProjects);
        }
    }

    /**
     * checks if <code>pomFile</code> is part of current maven and git context
     *
     * @param pomFile the pom file
     * @return true if <code>pomFile</code> is part of current maven and git context
     */
    private boolean isRelatedPom(File pomFile) throws IOException {
        return pomFile != null
                && pomFile.exists()
                && pomFile.isFile()
                // only project pom files ends in .xml, pom files from dependencies from repositories ends in .pom
                && pomFile.getName().endsWith(".xml")
                && pomFile.getCanonicalPath().startsWith(mvnDirectory.getParentFile().getCanonicalPath() + File.separator)
                // only pom files within git directory are treated as project pom files
                && pomFile.getCanonicalPath().startsWith(gitHeadSituation.getRootDirectory().getCanonicalPath() + File.separator);
    }

    private Model searchParentProjectInParentDirectory(Model projectModel) throws IOException {
        // search for parent project by directory hierarchy
        File parentDirectoryPomFile = pomFile(projectModel.getProjectDirectory().getParentFile(), "pom.xml");
        if (parentDirectoryPomFile.exists() && isRelatedPom(parentDirectoryPomFile)) {
            // check if parent has module that points to current project directory
            Model parentDirectoryProjectModel = readModel(parentDirectoryPomFile);
            for (File modulePomFile : getProjectModules(parentDirectoryProjectModel)) {
                if (modulePomFile.getCanonicalFile().equals(projectModel.getPomFile().getCanonicalFile())) {
                    return parentDirectoryProjectModel;
                }
            }
        }
        return null;
    }

    private static File getParentProjectPomFile(Model projectModel) {
        if (projectModel.getParent() == null) {
            return null;
        }

        File parentProjectPomFile = pomFile(projectModel.getProjectDirectory(), projectModel.getParent().getRelativePath());
        if (parentProjectPomFile.exists()) {
            return parentProjectPomFile;
        }

        return null;
    }

    private static Set<File> getProjectModules(Model projectModel) {
        final Set<File> modules = new HashSet<>();

        // modules section
        for (String module : projectModel.getModules()) {
            modules.add(pomFile(projectModel.getProjectDirectory(), module));
        }

        // profiles section
        for (Profile profile : projectModel.getProfiles()) {

            // modules section
            for (String module : profile.getModules()) {
                modules.add(pomFile(projectModel.getProjectDirectory(), module));
            }
        }

        return modules;
    }


    // ---- generate git versioned pom file ----------------------------------------------------------------------------

    private File writePomFile(Model projectModel) throws IOException {
        File gitVersionedPomFile = new File(projectModel.getProjectDirectory(), GIT_VERSIONING_POM_NAME);
        logger.debug("generate " + gitVersionedPomFile);

        // read original pom file
        Document gitVersionedPomDocument = readXml(projectModel.getPomFile());
        Element projectElement = gitVersionedPomDocument.getChild("project");

        // update project
        updateParentVersion(projectElement, projectModel.getParent());
        updateVersion(projectElement, projectModel);
        updatePropertyValues(projectElement, projectModel);
        updateDependencyVersions(projectElement, projectModel);
        updatePluginVersions(projectElement, projectModel.getBuild(), projectModel.getReporting());

        updateProfiles(projectElement, projectModel.getProfiles());

        writeXml(gitVersionedPomFile, gitVersionedPomDocument);

        return gitVersionedPomFile;
    }

    private static void updateParentVersion(Element projectElement, Parent parent) {
        Element parentElement = projectElement.getChild("parent");
        if (parentElement != null) {
            Element parentVersionElement = parentElement.getChild("version");
            parentVersionElement.setText(parent.getVersion());
        }
    }

    private static void updateVersion(Element projectElement, Model projectModel) {
        Element versionElement = projectElement.getChild("version");
        if (versionElement != null) {
            versionElement.setText(projectModel.getVersion());
        }
    }

    private void updatePropertyValues(Element element, ModelBase model) {
        // properties section
        Element propertiesElement = element.getChild("properties");
        if (propertiesElement != null) {
            Properties modelProperties = model.getProperties();
            gitVersionDetails.getConfig().property.forEach(property -> {
                String propertyName = property.name;
                Element propertyElement = propertiesElement.getChild(propertyName);
                if (propertyElement != null) {
                    String pomPropertyValue = propertyElement.getText();
                    String modelPropertyValue = (String) modelProperties.get(propertyName);
                    if (!Objects.equals(modelPropertyValue, pomPropertyValue)) {
                        propertyElement.setText(modelPropertyValue);
                    }
                }
            });
        }
    }

    private static void updateDependencyVersions(Element element, ModelBase model) {
        // dependencies section
        {
            Element dependenciesElement = element.getChild("dependencies");
            if (dependenciesElement != null) {
                updateDependencyVersions(dependenciesElement, model.getDependencies());
            }
        }
        // dependencyManagement section
        Element dependencyManagementElement = element.getChild("dependencyManagement");
        if (dependencyManagementElement != null) {
            Element dependenciesElement = dependencyManagementElement.getChild("dependencies");
            if (dependenciesElement != null) {
                updateDependencyVersions(dependenciesElement, model.getDependencyManagement().getDependencies());
            }
        }
    }

    private static void updateDependencyVersions(Element dependenciesElement, List<Dependency> dependencies) {
        forEachPair(dependenciesElement.getChildren(), dependencies, (dependencyElement, dependency) -> {
            // sanity check
            if (!Objects.equals(dependency.getManagementKey(), getDependencyManagementKey(dependencyElement))) {
                throw new IllegalArgumentException("Unexpected difference of xml and model dependencies order");
            }

            Element dependencyVersionElement = dependencyElement.getChild("version");
            if (dependencyVersionElement != null) {
                dependencyVersionElement.setText(dependency.getVersion());
            }
        });
    }

    private static String getDependencyManagementKey(Element element) {
        Element groupId = element.getChild("groupId");
        Element artifactId = element.getChild("artifactId");
        Element type = element.getChild("type");
        Element classifier = element.getChild("classifier");
        return (groupId != null ? groupId.getText().trim() : "")
                + ":" + (artifactId != null ? artifactId.getText().trim() : "")
                + ":" + (type != null ? type.getText().trim() : "jar")
                + (classifier != null ? ":" + classifier.getText().trim() : "");
    }

    private static void updatePluginVersions(Element projectElement, BuildBase build, Reporting reporting) {
        // build section
        Element buildElement = projectElement.getChild("build");
        if (buildElement != null) {
            // plugins section
            {
                Element pluginsElement = buildElement.getChild("plugins");
                if (pluginsElement != null) {
                    updatePluginVersions(pluginsElement, build.getPlugins());
                }
            }
            // pluginManagement section
            Element pluginsManagementElement = buildElement.getChild("pluginsManagement");
            if (pluginsManagementElement != null) {
                Element pluginsElement = pluginsManagementElement.getChild("plugins");
                if (pluginsElement != null) {
                    updatePluginVersions(pluginsElement, build.getPluginManagement().getPlugins());
                }
            }
        }

        Element reportingElement = projectElement.getChild("reporting");
        if (reportingElement != null) {
            // plugins section
            {
                Element pluginsElement = reportingElement.getChild("plugins");
                if (pluginsElement != null) {
                    updateReportPluginVersions(pluginsElement, reporting.getPlugins());
                }
            }
        }
    }

    private static void updatePluginVersions(Element pluginsElement, List<Plugin> plugins) {
        forEachPair(pluginsElement.getChildren(), plugins, (pluginElement, plugin) -> {
            // sanity check
            if (!Objects.equals(plugin.getKey(), getPluginKey(pluginElement))) {
                throw new IllegalArgumentException("Unexpected difference of xml and model plugin order");
            }

            Element pluginVersionElement = pluginElement.getChild("version");
            if (pluginVersionElement != null) {
                pluginVersionElement.setText(plugin.getVersion());
            }
        });
    }

    private static void updateReportPluginVersions(Element pluginsElement, List<ReportPlugin> plugins) {
        forEachPair(pluginsElement.getChildren(), plugins, (pluginElement, plugin) -> {
            // sanity check
            if (!Objects.equals(plugin.getKey(), getPluginKey(pluginElement))) {
                throw new IllegalArgumentException("Unexpected difference of xml and model plugin order");
            }

            Element pluginVersionElement = pluginElement.getChild("version");
            if (pluginVersionElement != null) {
                pluginVersionElement.setText(plugin.getVersion());
            }
        });
    }

    private static String getPluginKey(Element element) {
        Element groupId = element.getChild("groupId");
        Element artifactId = element.getChild("artifactId");
        return (groupId != null ? groupId.getText().trim() : "org.apache.maven.plugins")
                + ":" + (artifactId != null ? artifactId.getText().trim() : "");
    }

    private void updateProfiles(Element projectElement, List<Profile> profiles) {
        Element profilesElement = projectElement.getChild("profiles");
        if (profilesElement != null) {
            Map<String, Profile> profileMap = profiles.stream()
                    .collect(toMap(Profile::getId, it -> it));
            for (Element profileElement : profilesElement.getChildren("profile")) {
                Profile profile = profileMap.get(profileElement.getChild("id").getText());
                updatePropertyValues(profileElement, profile);
                updateDependencyVersions(profileElement, profile);
                updatePluginVersions(profileElement, profile.getBuild(), profile.getReporting());
            }
        }
    }


    // ---- misc -------------------------------------------------------------------------------------------------------

    private static String extensionLogHeader(GAV extensionGAV) {
        String extension = extensionGAV.toString();
        String metaInfo = "[core extension]";

        String plainLog = extension + " " + metaInfo;
        String formattedLog = buffer()
                .a(" ").mojo(extension).a(" ").strong(metaInfo).a(" ")
                .toString();

        return padLogHeaderPadding(plainLog, formattedLog);
    }

    private static String padLogHeaderPadding(String plainLog, String formattedLog) {
        String pad = "-";
        int padding = max(6, 72 - 2 - plainLog.length());
        int paddingLeft = (int) floor(padding / 2.0);
        int paddingRight = (int) ceil(padding / 2.0);
        return buffer()
                .strong(repeat(pad, paddingLeft))
                .a(formattedLog)
                .strong(repeat(pad, paddingRight))
                .toString();
    }

    private static String projectLogHeader(GAV projectGAV) {
        String project = projectGAV.getProjectId();
        return buffer().project(project).toString();
    }

    private static String sectionLogHeader(String title, ModelBase model) {
        String header = title + ":";
        if (model instanceof Profile) {
            header = buffer().strong("profile " + ((Profile) model).getId() + " ") + header;
        }
        return header;
    }

    private static String slugify(String value) {
        return value
                .replace("/", "-")
                .toLowerCase();
    }


    // ---- utils ------------------------------------------------------------------------------------------------------

    public static <T1, T2> void forEachPair(Collection<T1> collection1, Collection<T2> collection2, BiConsumer<T1, T2> consumer) {
        if (collection1.size() != collection2.size()) {
            throw new IllegalArgumentException("Collections sizes are not equals");
        }

        Iterator<T1> iter1 = collection1.iterator();
        Iterator<T2> iter2 = collection2.iterator();
        while (iter1.hasNext() && iter2.hasNext()) {
            consumer.accept(iter1.next(), iter2.next());
        }
    }
}
