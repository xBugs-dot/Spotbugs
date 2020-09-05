package edu.umd.cs.findbugs.sarif;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.umd.cs.findbugs.BugInstance;
import edu.umd.cs.findbugs.BugPattern;
import edu.umd.cs.findbugs.DetectorFactoryCollection;
import edu.umd.cs.findbugs.FindBugs2;
import edu.umd.cs.findbugs.Plugin;
import edu.umd.cs.findbugs.PluginException;
import edu.umd.cs.findbugs.PluginLoader;
import edu.umd.cs.findbugs.Priorities;
import edu.umd.cs.findbugs.Project;
import edu.umd.cs.findbugs.Version;
import edu.umd.cs.findbugs.ba.AnalysisContext;
import edu.umd.cs.findbugs.ba.SourceFinder;
import edu.umd.cs.findbugs.classfile.ClassDescriptor;
import edu.umd.cs.findbugs.classfile.DescriptorFactory;
import edu.umd.cs.findbugs.classfile.Global;
import edu.umd.cs.findbugs.classfile.IAnalysisCache;
import edu.umd.cs.findbugs.classfile.impl.ClassFactory;
import edu.umd.cs.findbugs.classfile.impl.ClassPathImpl;
import edu.umd.cs.findbugs.internalAnnotations.DottedClassName;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.startsWith;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

public class SarifBugReporterTest {
    private SarifBugReporter reporter;
    private StringWriter writer;

    @Before
    public void setup() {
        Project project = new Project();
        reporter = new SarifBugReporter(project);
        writer = new StringWriter();
        reporter.setWriter(new PrintWriter(writer));
        reporter.setPriorityThreshold(Priorities.IGNORE_PRIORITY);
        DetectorFactoryCollection.resetInstance(new DetectorFactoryCollection());
        IAnalysisCache analysisCache = ClassFactory.instance().createAnalysisCache(new ClassPathImpl(), reporter);
        Global.setAnalysisCacheForCurrentThread(analysisCache);
        FindBugs2.registerBuiltInAnalysisEngines(analysisCache);
        AnalysisContext analysisContext = new AnalysisContext(project) {
            public boolean isApplicationClass(@DottedClassName String className) {
                // treat all classes as application class, to report bugs in it
                return true;
            }
        };
        AnalysisContext.setCurrentAnalysisContext(analysisContext);
    }

    @After
    public void teardown() {
        AnalysisContext.removeCurrentAnalysisContext();
        Global.removeAnalysisCacheForCurrentThread();
    }

    /**
     * Root object&apos;s first field should be {@code "version"}, and it should be {@code "2.1.0"}.
     * Root object also should have {@code "$schema"} field that points the JSON schema provided by SARIF community.
     */
    @Test
    public void testVersionAndSchema() throws JsonProcessingException {
        reporter.finish();

        String json = writer.toString();
        SarifSchema210 schema = new ObjectMapper().readValue(json, SarifSchema210.class);

        assertThat("the first key in JSON should be 'version'", json, startsWith("{\"version\""));
        assertThat(schema.getVersion().value(), is("2.1.0"));
        assertThat(schema.get$schema().toString(), is(
                "https://raw.githubusercontent.com/oasis-tcs/sarif-spec/master/Schemata/sarif-schema-2.1.0.json"));
    }

    /**
     * {@code toolComponent} object in {@code "runs.tool.driver"} SHOULD have {@code "version"} (§3.19.2).
     * A toolComponent object SHALL contain a {@code "name"} property (§3.19.8).
     * A toolComponent object MAY contain a {@code "language"} property (§3.19.21).
     */
    @Test
    public void testDriver() throws JsonProcessingException {
        final String EXPECTED_VERSION = Version.VERSION_STRING;
        final String EXPECTED_LANGUAGE = "ja";

        Locale defaultLocale = Locale.getDefault();
        try {
            Locale.setDefault(Locale.JAPANESE);
            reporter.finish();
        } finally {
            Locale.setDefault(defaultLocale);
        }

        String json = writer.toString();
        SarifSchema210 schema = new ObjectMapper().readValue(json, SarifSchema210.class);
        ToolComponent driver = schema.getRuns().get(0).getTool().getDriver();

        assertThat(driver.getName(), is("SpotBugs"));
        assertThat(driver.getVersion(), is(EXPECTED_VERSION));
        assertThat(driver.getLanguage(), is(EXPECTED_LANGUAGE));
    }

    @Test
    public void testRuleWithArguments() throws JsonProcessingException {
        // given
        final String EXPECTED_BUG_TYPE = "BUG_TYPE";
        final int EXPECTED_PRIORITY = Priorities.NORMAL_PRIORITY;
        final String EXPECTED_DESCRIPTION = "describing about this bug type...";
        BugPattern bugPattern = new BugPattern(EXPECTED_BUG_TYPE, "abbrev", "category", false, EXPECTED_DESCRIPTION,
                "describing about this bug type with value {0}...", "detailText", null, 0);
        DetectorFactoryCollection.instance().registerBugPattern(bugPattern);

        // when
        reporter.reportBug(new BugInstance(bugPattern.getType(), bugPattern.getPriorityAdjustment()).addInt(10).addClass("the/target/Class"));
        reporter.finish();

        // then
        String json = writer.toString();
        SarifSchema210 schema = new ObjectMapper().readValue(json, SarifSchema210.class);
        Set<ReportingDescriptor> rules = schema.getRuns().get(0).getTool().getDriver().getRules();

        assertThat(rules.size(), is(1));
        ReportingDescriptor rule = rules.stream().findFirst().get();
        assertThat(rule.getId(), is(bugPattern.getType()));
        String defaultText = rule.getMessageStrings().getAdditionalProperties().get("default").getText();
        assertThat(defaultText, is("describing about this bug type with value {0}..."));

        List<Result> results = schema.getRuns().get(0).getResults();
        assertThat(results.size(), is(1));
        Result result = results.get(0);
        assertThat(result.getRuleId(), is(bugPattern.getType()));
        Message message = result.getMessage();
        assertThat(message.getId(), is("default"));
        assertThat(message.getArguments().get(0), is("10"));
    }

    @Test
    public void testMissingClassNotification() throws JsonProcessingException {
        ClassDescriptor classDescriptor = DescriptorFactory.instance().getClassDescriptor("com/github/spotbugs/MissingClass");
        reporter.reportMissingClass(classDescriptor);
        reporter.finish();

        // then
        String json = writer.toString();
        SarifSchema210 schema = new ObjectMapper().readValue(json, SarifSchema210.class);
        List<Notification> toolConfigurationNotifications = schema.getRuns().get(0).getInvocations().get(0).getToolConfigurationNotifications();

        assertThat(toolConfigurationNotifications.size(), is(1));
        Notification notification = toolConfigurationNotifications.get(0);
        assertThat(notification.getDescriptor().getId(), is("spotbugs-missing-classes"));
        assertThat(notification.getMessage().getText(), is(
                "Classes needed for analysis were missing: [com.github.spotbugs.MissingClass]"));
    }

    @Test
    public void testErrorNotification() throws JsonProcessingException {
        reporter.logError("Unexpected Error");
        reporter.finish();

        String json = writer.toString();
        SarifSchema210 schema = new ObjectMapper().readValue(json, SarifSchema210.class);
        List<Notification> toolExecutionNotifications = schema.getRuns().get(0).getInvocations().get(0).getToolExecutionNotifications();

        assertThat(toolExecutionNotifications.size(), is(1));
        Notification notification = toolExecutionNotifications.get(0);
        assertThat(notification.getDescriptor().getId(), is("spotbugs-error-0"));
        assertThat(notification.getMessage().getText(), is("Unexpected Error"));
        assertNull(notification.getException());
    }

    @Test
    public void testExceptionNotification() throws JsonProcessingException {
        reporter.getProject().getSourceFinder().setSourceBaseList(Collections.singletonList(new File("src/test/java").getAbsolutePath()));
        reporter.logError("Unexpected Error", new java.lang.Exception("Unexpected Problem"));
        reporter.finish();

        String json = writer.toString();
        SarifSchema210 schema = new ObjectMapper().readValue(json, SarifSchema210.class);
        List<Notification> toolExecutionNotifications = schema.getRuns().get(0).getInvocations().get(0).getToolExecutionNotifications();

        assertThat(toolExecutionNotifications.size(), is(1));
        Notification notification = toolExecutionNotifications.get(0);

        assertThat(notification.getDescriptor().getId(), is("spotbugs-error-0"));
        assertThat(notification.getMessage().getText(), is("Unexpected Error"));
        assertNotNull(notification.getException());
        List<StackFrame> frames = notification.getException().getStack().getFrames();
        PhysicalLocation physicalLocation = frames.get(0).getLocation().getPhysicalLocation();
        String uri = physicalLocation.getArtifactLocation().getUri();
        assertThat(uri, is("edu/umd/cs/findbugs/sarif/SarifBugReporterTest.java"));
    }

    @Test
    public void testExceptionNotificationWithoutMessage() throws JsonProcessingException {
        reporter.logError("Unexpected Error", new java.lang.Exception());
        reporter.finish();

        String json = writer.toString();
        SarifSchema210 schema = new ObjectMapper().readValue(json, SarifSchema210.class);

        List<Notification> toolExecutionNotifications = schema.getRuns().get(0).getInvocations().get(0).getToolExecutionNotifications();

        assertThat(toolExecutionNotifications.size(), is(1));
        Notification notification = toolExecutionNotifications.get(0);
        assertThat(notification.getDescriptor().getId(), is("spotbugs-error-0"));
        assertThat(notification.getMessage().getText(), is("Unexpected Error"));
        assertNotNull(notification.getException());
    }

    @Test
    public void testHelpUriAndTags() throws JsonProcessingException {
        BugPattern bugPattern = new BugPattern("TYPE", "abbrev", "category", false, "shortDescription",
                "longDescription", "detailText", "https://example.com/help.html", 0);
        DetectorFactoryCollection.instance().registerBugPattern(bugPattern);

        reporter.reportBug(new BugInstance(bugPattern.getType(), bugPattern.getPriorityAdjustment()).addInt(10).addClass("the/target/Class"));
        reporter.finish();

        String json = writer.toString();
        SarifSchema210 schema = new ObjectMapper().readValue(json, SarifSchema210.class);
        Set<ReportingDescriptor> rules = schema.getRuns().get(0).getTool().getDriver().getRules();

        assertThat(rules.size(), is(1));
        ReportingDescriptor rule = rules.stream().findFirst().get();
        assertThat(rule.getHelpUri().toString(), is("https://example.com/help.html#TYPE"));

        Set<String> tags = rule.getProperties().getTags();
        assertThat(tags.size(), is(1));
        assertThat(tags.stream().findFirst().get(), is("category"));
    }

    @Test
    public void testExtensions() throws PluginException, JsonProcessingException {
        PluginLoader pluginLoader = DetectorFactoryCollection.instance().getCorePlugin().getPluginLoader();
        Plugin plugin = new Plugin("pluginId", "version", null, pluginLoader, true, false);
        DetectorFactoryCollection dfc = new DetectorFactoryCollection(plugin);
        try {
            DetectorFactoryCollection.resetInstance(dfc);
            reporter.finish();
        } finally {
            DetectorFactoryCollection.resetInstance(null);
        }

        String json = writer.toString();
        SarifSchema210 schema = new ObjectMapper().readValue(json, SarifSchema210.class);
        Set<ToolComponent> extensions = schema.getRuns().get(0).getTool().getExtensions();

        assertThat(extensions.size(), is(1));
        ToolComponent extension = extensions.stream().findFirst().get();

        assertThat(extension.getName(), is("pluginId"));
        assertThat(extension.getVersion(), is("version"));
    }

    @Test
    public void testSourceLocation() throws IOException {
        Path tmpDir = Files.createTempDirectory("spotbugs");
        new File(tmpDir.toFile(), "SampleClass.java").createNewFile();
        SourceFinder sourceFinder = reporter.getProject().getSourceFinder();
        sourceFinder.setSourceBaseList(Collections.singleton(tmpDir.toString()));

        BugPattern bugPattern = new BugPattern("TYPE", "abbrev", "category", false, "shortDescription",
                "longDescription", "detailText", "https://example.com/help.html", 0);
        DetectorFactoryCollection.instance().registerBugPattern(bugPattern);

        reporter.reportBug(new BugInstance(bugPattern.getType(), bugPattern.getPriorityAdjustment()).addInt(10).addClass("SampleClass"));
        reporter.finish();

        String json = writer.toString();
        SarifSchema210 schema = new ObjectMapper().readValue(json, SarifSchema210.class);
        Run run = schema.getRuns().get(0);
        Map<String, ArtifactLocation> originalUriBaseIds = run.getOriginalUriBaseIds().getAdditionalProperties();
        String uriBaseId = originalUriBaseIds.keySet().stream().findFirst().get();
        ArtifactLocation artifactLocation = originalUriBaseIds.get(uriBaseId);
        assertThat(URI.create(artifactLocation.getUri()), is(tmpDir.toUri()));

        List<Result> results = run.getResults();
        assertThat(results.size(), is(1));
        artifactLocation = results.get(0).getLocations().get(0).getPhysicalLocation().getArtifactLocation();
        String relativeUri = artifactLocation.getUri();
        assertThat("relative URI that can be resolved by the uriBase",
                relativeUri, is("SampleClass.java"));
        assertThat(artifactLocation.getUriBaseId(), is(uriBaseId));
    }
}
