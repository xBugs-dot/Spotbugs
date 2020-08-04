module com.github.spotbugs.spotbugs {
    exports edu.umd.cs.findbugs;
    exports edu.umd.cs.findbugs.config;
    exports edu.umd.cs.findbugs.plugins;

    // export for spotbugs-ant
    exports edu.umd.cs.findbugs.workflow;

    requires com.github.spotbugs.annotations;
    requires java.desktop;
    requires java.logging;
    requires java.management;
    requires java.xml;
    requires jdk.management;
    requires jsr305;
    requires org.objectweb.asm;
    requires org.objectweb.asm.tree;
    requires org.slf4j;
}