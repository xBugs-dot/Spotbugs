package edu.umd.cs.findbugs.sarif;

import edu.umd.cs.findbugs.BugCollection;
import edu.umd.cs.findbugs.BugInstance;
import edu.umd.cs.findbugs.BugPattern;
import edu.umd.cs.findbugs.BugRankCategory;
import edu.umd.cs.findbugs.BugRanker;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.ba.SourceFinder;
import org.apache.commons.lang3.StringUtils;

import java.net.URI;
import java.util.*;
import java.util.stream.Collectors;

class BugCollectionAnalyser {
    @NonNull
    private final List<ReportingDescriptor> rules = new ArrayList<>();
    @NonNull
    private final List<Result> results = new ArrayList<>();
    @NonNull
    private final Map<String, Integer> typeToIndex = new HashMap<>();
    @NonNull
    private final List<List<Placeholder>> indexToPlaceholders = new ArrayList<>();

    /**
     * Map baseURI to uriBaseId. e.g. {@code "/user/ubuntu/github/spotbugs/" -> "8736793520"}
     */
    @NonNull
    private final Map<URI, String> baseToId = new HashMap<>();

    BugCollectionAnalyser(@NonNull BugCollection bugCollection) {
        SourceFinder sourceFinder = bugCollection.getProject().getSourceFinder();
        bugCollection.forEach(bug -> {
            String type = bug.getType();
            int index = typeToIndex.computeIfAbsent(type, t -> processRule(bug.getBugPattern()));

            processResult(index, bug, sourceFinder);
        });
    }

    List<ReportingDescriptor> getRules() {
        return rules;
    }

    List<Result> getResults() {
        return results;
    }

    @NonNull
    OriginalUriBaseIds getOriginalUriBaseIds() {
        OriginalUriBaseIds result = new OriginalUriBaseIds();
        baseToId.forEach((uri, uriBaseId) -> result.withAdditionalProperty(uriBaseId, new ArtifactLocation().withUri(uri.toString()).withUriBaseId(
                uriBaseId)));
        return result;
    }

    private void processResult(int index, BugInstance bug, SourceFinder sourceFinder) {
        List<String> arguments = indexToPlaceholders.get(index).stream()
                .map(placeholder -> placeholder.toArgument(bug.getAnnotations(), bug.getPrimaryClass()))
                .collect(Collectors.toList());
        List<Location> locations = new ArrayList<>();
        new LocationHandler().generateLocation(bug, sourceFinder, baseToId).ifPresent(locations::add);
        int bugRank = BugRanker.findRank(bug);
        Result result = new Result().withRuleId(bug.getType()).withRuleIndex(index).withMessage(new Message().withArguments(arguments)).withLocations(
                locations).withLevel(toLevel(bugRank));
        results.add(result);
    }

    private int processRule(BugPattern bugPattern) {
        assert indexToPlaceholders.size() == rules.size();
        int ruleIndex = rules.size();

        List<Placeholder> placeholders = new ArrayList<>();
        MessageFormat formatter = new MessageFormat(bugPattern.getLongDescription());
        String formattedMessage = formatter.format((Integer index, String key) -> {
            int indexOfPlaceholder = placeholders.size();
            placeholders.add(new Placeholder(index, key));
            return String.format("{%d}", indexOfPlaceholder);
        });
        ReportingDescriptor rule = generateRule(bugPattern, formattedMessage);
        rules.add(rule);
        indexToPlaceholders.add(placeholders);

        return ruleIndex;
    }

    Map<URI, String> getBaseToId() {
        return baseToId;
    }

    static Result.Level toLevel(int bugRank) {
        BugRankCategory category = BugRankCategory.getRank(bugRank);
        switch (category) {
        case SCARIEST:
        case SCARY:
            return Result.Level.ERROR;
        case TROUBLING:
            return Result.Level.WARNING;
        case OF_CONCERN:
            return Result.Level.NOTE;
        default:
            throw new IllegalArgumentException("Illegal bugRank given: " + bugRank);
        }
    }

    private ReportingDescriptor generateRule(BugPattern bugPattern, String formattedMessage) {
        URI helpUri = bugPattern.getUri().orElse(null);

        String category = bugPattern.getCategory();
        PropertyBag properties = new PropertyBag();
        if (!StringUtils.isEmpty(category)) {
            properties.withTags(Collections.singleton(category));
        }

        return new ReportingDescriptor()
                .withId(bugPattern.getType())
                .withShortDescription(new MultiformatMessageString().withText(bugPattern.getShortDescription()))
                .withFullDescription(new MultiformatMessageString().withText(bugPattern.getDetailText()))
                .withMessageStrings(new MessageStrings()) // TODO put 'formattedMessage' into this MessageStrings
                .withProperties(properties)
                .withHelpUri(helpUri);
    }
}
