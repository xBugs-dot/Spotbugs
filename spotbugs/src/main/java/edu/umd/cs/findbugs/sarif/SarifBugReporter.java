package edu.umd.cs.findbugs.sarif;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.json.JsonMapper;
import edu.umd.cs.findbugs.AbstractBugReporter;
import edu.umd.cs.findbugs.BugCollectionBugReporter;
import edu.umd.cs.findbugs.DetectorFactoryCollection;
import edu.umd.cs.findbugs.ExitCodes;
import edu.umd.cs.findbugs.Plugin;
import edu.umd.cs.findbugs.Project;
import edu.umd.cs.findbugs.Version;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import edu.umd.cs.findbugs.ba.SourceFinder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

public class SarifBugReporter extends BugCollectionBugReporter {
    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final LocationHandler locationHandler = new LocationHandler();

    public SarifBugReporter(Project project) {
        super(project);
    }

    @Override
    public void finish() {
        try (JsonGenerator jGenerator = new JsonFactory(new JsonMapper())
                .createGenerator(outputStream)) {
            jGenerator.writeStartObject();
            jGenerator.writeStringField("version", "2.1.0");
            jGenerator.writeStringField("$schema",
                    "https://raw.githubusercontent.com/oasis-tcs/sarif-spec/master/Schemata/sarif-schema-2.1.0.json");
            processRuns(jGenerator);
            jGenerator.writeEndObject();;
            getBugCollection().bugsPopulated();
        } catch (IOException e) {
            logger.warn("Failed to generate the SARIF report", e);
        }
    }

    private void processRuns(@NonNull JsonGenerator jGenerator) throws IOException {
        jGenerator.writeFieldName("runs");
        jGenerator.writeStartArray();
        jGenerator.writeStartObject();
        BugCollectionAnalyser analyser = new BugCollectionAnalyser(getBugCollection());
        processTool(jGenerator, analyser.getRules());
        processInvocations(jGenerator, analyser.getBaseToId());
        jGenerator.writeObjectField("results", analyser.getResults());
        jGenerator.writeObjectField("originalUriBaseIds", analyser.getOriginalUriBaseIds());
        jGenerator.writeEndObject();
        jGenerator.writeEndArray();
    }

    private void processInvocations(@NonNull JsonGenerator jGenerator, @NonNull Map<URI, String> baseToId) throws IOException {
        List<Notification> configNotifications = new ArrayList<>();

        Set<String> missingClasses = getMissingClasses();
        if (missingClasses == null) {
            missingClasses = Collections.emptySet();
        }
        if (!missingClasses.isEmpty()) {
            String message = String.format("Classes needed for analysis were missing: %s", missingClasses.toString());
            configNotifications.add(generateNotification("spotbugs-missing-classes", message, Notification.Level.ERROR, null));
        }

        List<Notification> execNotifications = getQueuedErrors().stream()
                .map(t -> generateNotification(t, getProject().getSourceFinder(), baseToId))
                .collect(Collectors.toList());

        int exitCode = ExitCodes.from(getQueuedErrors().size(), missingClasses.size(), getBugCollection().getCollection().size());
        Invocation invocation = new Invocation().withExitCode(exitCode)
                .withExitSignalName(getSignalName(exitCode)).withExecutionSuccessful(exitCode == 0)
                .withToolExecutionNotifications(execNotifications)
                .withToolConfigurationNotifications(configNotifications);
        jGenerator.writeFieldName("invocations");
        jGenerator.writeStartArray();
        jGenerator.writeObject(invocation);
        jGenerator.writeEndArray();
    }

    private void processTool(@NonNull JsonGenerator jGenerator, @NonNull List<ReportingDescriptor> rules) throws IOException {
        jGenerator.writeFieldName("tool");
        jGenerator.writeStartObject();
        processExtensions(jGenerator);
        jGenerator.writeFieldName("driver");
        jGenerator.writeStartObject();
        jGenerator.writeStringField("name", "SpotBugs");
        // Eclipse plugin does not follow the semantic-versioning, so use "version" instead of "semanticVersion".
        jGenerator.writeStringField("version", Version.VERSION_STRING);
        // SpotBugs refers JVM config to decide which language we use.
        jGenerator.writeStringField("language", Locale.getDefault().getLanguage());
        jGenerator.writeObjectField("rules", rules);
        jGenerator.writeEndObject();
        jGenerator.writeEndObject();
    }

    private void processExtensions(@NonNull JsonGenerator jGenerator) throws IOException {
        jGenerator.writeFieldName("extensions");
        List<ToolComponent> extensions = DetectorFactoryCollection.instance().plugins().stream().map(this::createToolComponent).collect(Collectors
                .toList());
        jGenerator.writeStartArray(extensions.size());
        for (ToolComponent extension : extensions) {
            jGenerator.writeObject(extension);
        }
        jGenerator.writeEndArray();
    }

    private ToolComponent createToolComponent(Plugin plugin) {
        return new ToolComponent()
                .withVersion(plugin.getVersion())
                .withName(plugin.getPluginId())
                .withShortDescription(new MultiformatMessageString().withText(plugin.getShortDescription()))
                .withInformationUri(plugin.getWebsiteURI())
                .withOrganization(plugin.getProvider());
    }

    private static String getSignalName(int exitCode) {
        if (exitCode == 0) {
            return "SUCCESS";
        }

        List<String> list = new ArrayList<>();
        if ((exitCode | ExitCodes.ERROR_FLAG) > 0) {
            list.add("ERROR");
        }
        if ((exitCode | ExitCodes.MISSING_CLASS_FLAG) > 0) {
            list.add("MISSING CLASS");
        }
        if ((exitCode | ExitCodes.BUGS_FOUND_FLAG) > 0) {
            list.add("BUGS FOUND");
        }

        return list.isEmpty() ? "UNKNOWN" : list.stream().collect(Collectors.joining(","));
    }

    private Notification generateNotification(@NonNull AbstractBugReporter.Error error, @NonNull SourceFinder sourceFinder,
            @NonNull Map<URI, String> baseToId) {
        String id = String.format("spotbugs-error-%d", error.getSequence());
        Throwable cause = error.getCause();
        Notification result = new Notification().withDescriptor(new ReportingDescriptorReference().withId(id)).withMessage(new Message().withText(
                error.getMessage())).withLevel(Notification.Level.ERROR);
        if (cause != null) {
            result = result.withException(generateException(cause, sourceFinder, baseToId));
        }
        return result;
    }

    private Exception generateException(@NonNull Throwable throwable, @NonNull SourceFinder sourceFinder, @NonNull Map<URI, String> baseToId) {
        String message = throwable.getMessage();
        if (message == null) {
            message = "no message given";
        }

        List<Throwable> innerThrowables = new ArrayList<>();
        innerThrowables.add(throwable.getCause());
        innerThrowables.addAll(Arrays.asList(throwable.getSuppressed()));
        List<Exception> innerExceptions = innerThrowables.stream()
                .filter(Objects::nonNull)
                .map(t -> generateException(t, sourceFinder, baseToId))
                .collect(Collectors.toList());
        return new Exception()
                .withKind(throwable.getClass().getName())
                .withMessage(message)
                .withStack(generateStack(throwable, sourceFinder, baseToId))
                .withInnerExceptions(innerExceptions);
    }

    private Notification generateNotification(@NonNull String descriptorId, @NonNull String message, @NonNull Notification.Level level,
            @Nullable Exception exception) {
        return new Notification()
                .withDescriptor(new ReportingDescriptorReference().withId(descriptorId))
                .withMessage(new Message().withText(message))
                .withLevel(level)
                .withException(exception);
    }

    private Stack generateStack(@NonNull Throwable throwable, @NonNull SourceFinder sourceFinder, @NonNull Map<URI, String> baseToId) {
        List<StackFrame> frames = Arrays.stream(Objects.requireNonNull(throwable).getStackTrace()).map(element -> generateStackFrame(
                element, sourceFinder, baseToId)).collect(
                        Collectors.toList());
        String message = throwable.getMessage();
        if (message == null) {
            message = "no message given";
        }
        return new Stack().withMessage(new Message().withText(message)).withFrames(frames);
    }

    private StackFrame generateStackFrame(@NonNull StackTraceElement element, @NonNull SourceFinder sourceFinder,
            @NonNull Map<URI, String> baseToId) {
        Location location = locationHandler.generateLocation(element, sourceFinder, baseToId);
        return new StackFrame().withLocation(location);
    }
}
