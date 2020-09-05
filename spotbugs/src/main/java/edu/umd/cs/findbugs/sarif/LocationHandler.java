package edu.umd.cs.findbugs.sarif;

import edu.umd.cs.findbugs.BugAnnotation;
import edu.umd.cs.findbugs.BugInstance;
import edu.umd.cs.findbugs.ClassAnnotation;
import edu.umd.cs.findbugs.FieldAnnotation;
import edu.umd.cs.findbugs.LocalVariableAnnotation;
import edu.umd.cs.findbugs.MethodAnnotation;
import edu.umd.cs.findbugs.SourceLineAnnotation;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.ba.SourceFile;
import edu.umd.cs.findbugs.ba.SourceFinder;
import edu.umd.cs.findbugs.util.ClassName;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * <p>The class which is responsible to map SpotBugs objects to SARIF location objects.</p>
 *
 * @see Location
 * @see PhysicalLocation
 * @see LogicalLocation
 */
class LocationHandler {
    Optional<Location> generateLocation(@NonNull BugInstance bugInstance, @NonNull SourceFinder sourceFinder,
            @NonNull Map<URI, String> baseToId) {
        Objects.requireNonNull(bugInstance);
        Objects.requireNonNull(sourceFinder);
        Objects.requireNonNull(baseToId);

        final PhysicalLocation physicalLocation = generatePhysicalLocation(bugInstance, sourceFinder, baseToId);
        return generateLogicalLocation(bugInstance).map(logicalLocation -> new Location().withPhysicalLocation(physicalLocation)
                .withLogicalLocations(Collections.singleton(logicalLocation)));
    }

    @CheckForNull
    private PhysicalLocation generatePhysicalLocation(@NonNull BugInstance bugInstance, @NonNull SourceFinder sourceFinder,
            Map<URI, String> baseToId) {
        try {
            SourceLineAnnotation sourceLine = bugInstance.getPrimarySourceLineAnnotation();
            return generatePhysicalLocation(sourceLine, sourceFinder, baseToId).orElse(null);
        } catch (IllegalStateException e) {
            // no sourceline info found
            return null;
        }
    }

    private Optional<PhysicalLocation> generatePhysicalLocation(SourceLineAnnotation bugAnnotation, SourceFinder sourceFinder,
            Map<URI, String> baseToId) {
        Optional<ArtifactLocation> artifactLocation = generateArtifactLocation(bugAnnotation, sourceFinder, baseToId);
        Optional<Region> region = generateRegion(bugAnnotation);
        return artifactLocation.map(location -> new PhysicalLocation().withArtifactLocation(location).withRegion(region.orElse(null)));
    }

    private Optional<ArtifactLocation> generateArtifactLocation(@NonNull SourceLineAnnotation bugAnnotation, @NonNull SourceFinder sourceFinder,
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

    private Optional<Region> generateRegion(SourceLineAnnotation annotation) {
        if (annotation.getStartLine() <= 0 || annotation.getEndLine() <= 0) {
            return Optional.empty();
        } else {
            return Optional.of(new Region().withStartLine(annotation.getStartLine()).withEndLine(annotation.getEndLine()));
        }
    }

    private Optional<LogicalLocation> generateLogicalLocation(@NonNull BugInstance bugInstance) {
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

    Location generateLocation(@NonNull StackTraceElement element, @NonNull SourceFinder sourceFinder,
            @NonNull Map<URI, String> baseToId) {
        Objects.requireNonNull(element);
        Objects.requireNonNull(sourceFinder);
        Objects.requireNonNull(baseToId);

        Optional<PhysicalLocation> physicalLocation = generatePhysicalLocation(element, sourceFinder, baseToId);
        LogicalLocation logicalLocation = generateLogicalLocation(element);
        return new Location().withPhysicalLocation(physicalLocation.orElse(null)).withLogicalLocations(Collections.singleton(logicalLocation));
    }

    private Optional<PhysicalLocation> generatePhysicalLocation(@NonNull StackTraceElement element, @NonNull SourceFinder sourceFinder,
            Map<URI, String> baseToId) {
        Optional<Region> region = Optional.of(element.getLineNumber())
                .filter(line -> line > 0)
                .map(line -> new Region().withStartLine(line).withEndLine(line));
        return new LocationHandler().generateArtifactLocation(element, sourceFinder, baseToId)
                .map(artifactLocation -> new PhysicalLocation().withArtifactLocation(artifactLocation).withRegion(region.orElse(null)));
    }

    private LogicalLocation generateLogicalLocation(@NonNull StackTraceElement element) {
        String fullyQualifiedName = String.format("%s.%s", element.getClassName(), element.getMethodName());
        PropertyBag properties = new PropertyBag().withAdditionalProperty("line-number", element.getLineNumber());
        return new LogicalLocation().withName(element.getMethodName()).withKind("function").withFullyQualifiedName(fullyQualifiedName).withProperties(
                properties);
    }

    Optional<ArtifactLocation> generateArtifactLocation(StackTraceElement element, SourceFinder sourceFinder, Map<URI, String> baseToId) {
        Objects.requireNonNull(element);
        Objects.requireNonNull(sourceFinder);
        Objects.requireNonNull(baseToId);

        String packageName = ClassName.extractPackageName(element.getClassName());
        String fileName = element.getFileName();
        try {
            SourceFile sourceFile = sourceFinder.findSourceFile(packageName, fileName);
            String fullFileName = sourceFile.getFullFileName();
            int index = fullFileName.indexOf(packageName.replace('.', File.separatorChar));
            assert index >= 0;
            String relativeFileName = fullFileName.substring(index);
            return sourceFinder.getBase(relativeFileName).map(base -> {
                String baseId = baseToId.computeIfAbsent(base, s -> Integer.toString(s.hashCode()));
                URI relativeUri = base.relativize(sourceFile.getFullURI());
                return new ArtifactLocation().withUri(relativeUri.toString()).withUriBaseId(baseId);
            });
        } catch (IOException fileNotFound) {
            return Optional.empty();
        }
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
