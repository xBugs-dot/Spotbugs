package edu.umd.cs.findbugs.sarif;

import edu.umd.cs.findbugs.*;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.ba.SourceFile;
import edu.umd.cs.findbugs.ba.SourceFinder;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

class LocationHandler {
    Optional<Location> toLocation(@NonNull BugInstance bugInstance, @NonNull SourceFinder sourceFinder,
                                          @NonNull Map<URI, String> baseToId) {
        Objects.requireNonNull(bugInstance);
        Objects.requireNonNull(sourceFinder);
        Objects.requireNonNull(baseToId);

        final PhysicalLocation physicalLocation = findPhysicalLocation(bugInstance, sourceFinder, baseToId);
        return toLogicalLocation(bugInstance).map(logicalLocation -> new Location().withPhysicalLocation(physicalLocation)
                .withLogicalLocations(Collections.singleton(logicalLocation)));
    }

    @CheckForNull
    private PhysicalLocation findPhysicalLocation(@NonNull BugInstance bugInstance, @NonNull SourceFinder sourceFinder,
                                                  Map<URI, String> baseToId) {
        try {
            SourceLineAnnotation sourceLine = bugInstance.getPrimarySourceLineAnnotation();
            return fromBugAnnotation(sourceLine, sourceFinder, baseToId).orElse(null);
        } catch (IllegalStateException e) {
            // no sourceline info found
            return null;
        }
    }

    private Optional<PhysicalLocation> fromBugAnnotation(SourceLineAnnotation bugAnnotation, SourceFinder sourceFinder,
                                                         Map<URI, String> baseToId) {
        Optional<ArtifactLocation> artifactLocation = toArtifactLocation(bugAnnotation, sourceFinder, baseToId);
        Optional<Region> region = toRegion(bugAnnotation);
        return artifactLocation.map(location -> new PhysicalLocation().withArtifactLocation(location).withRegion(region.orElse(null)));
    }

    private Optional<ArtifactLocation> toArtifactLocation(@NonNull SourceLineAnnotation bugAnnotation, @NonNull SourceFinder sourceFinder,
                                                          @NonNull Map<URI, String> baseToId) {
        Objects.requireNonNull(bugAnnotation);
        Objects.requireNonNull(sourceFinder);
        Objects.requireNonNull(baseToId);

        return sourceFinder.getBase(bugAnnotation).map(base -> {
            String uriBaseId = baseToId.computeIfAbsent(base, s -> Integer.toString(s.hashCode()));
            try {
                SourceFile sourceFile = sourceFinder.findSourceFile(bugAnnotation);
                return new ArtifactLocation().withUriBaseId(uriBaseId).withUri(base.relativize(sourceFile.getFullURI()).toString());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    private Optional<Region> toRegion(SourceLineAnnotation annotation) {
        if (annotation.getStartLine() <= 0 || annotation.getEndLine() <= 0) {
            return Optional.empty();
        } else {
            return Optional.of(new Region().withStartLine(annotation.getStartLine()).withEndLine(annotation.getEndLine()));
        }
    }

    private Optional<LogicalLocation> toLogicalLocation(@NonNull BugInstance bugInstance) {
        Objects.requireNonNull(bugInstance);
        ClassAnnotation primaryClass = bugInstance.getPrimaryClass();
        SourceLineAnnotation sourceLine = bugInstance.getPrimarySourceLineAnnotation();
        return bugInstance.getAnnotations().stream().map(annotation -> {
            String kind = findKind(annotation);
            if (kind == null) {
                return null;
            }
            String name = annotation.format("givenClass", primaryClass);
            return new LogicalLocation().withName(name).withDecoratedName(null).withKind(kind).withFullyQualifiedName(sourceLine.format("full",
                    primaryClass));
        }).filter(Objects::nonNull).findFirst();
    }

    @CheckForNull
    private String findKind(@NonNull BugAnnotation annotation) {
        if (annotation instanceof ClassAnnotation) {
            return "type";
        } else if (annotation instanceof MethodAnnotation) {
            return "function";
        } else if (annotation instanceof FieldAnnotation) {
            return "member";
        } else if (annotation instanceof LocalVariableAnnotation) {
            return "variable";
        } else {
            return null;
        }
    }
}
