package com.aicompany.core.agent.validation;

import com.aicompany.core.agent.model.DevelopmentResult.GeneratedFile;
import org.w3c.dom.Element;
import org.xml.sax.SAXException;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Gate de .csproj antes del commit (verificado en vivo con MISSION-SANDBOX-VERIFY-3): XML bien formado y cada
 * ProjectReference apuntando a uno de los proyectos esperados del perfil. Determinista, con reintento: el
 * error exacto vuelve al agente como corrección. Sin DOCTYPE ni entidades externas.
 */
public final class ProjectFileGate {

    private ProjectFileGate() {
    }

    public static List<String> check(List<GeneratedFile> files, List<String> expectedProjects) {

        var errors = new ArrayList<String>();

        for (var file : files.stream().filter(Objects::nonNull).toList()) {

            if (file.path() == null || !file.path().endsWith(".csproj")) {
                continue;
            }

            Element root;
            try {
                root = parse(file.content() == null ? "" : file.content());
            } catch (SAXException | IOException | ParserConfigurationException ex) {
                errors.add(file.path() + " no es XML válido (" + ex.getMessage() + "). Debe empezar directamente "
                        + "con <Project Sdk=\"...\"> (sin comentarios // ni texto antes) y estar completo.");
                continue;
            }

            if (expectedProjects.isEmpty()) {
                continue;
            }

            var dir = Path.of(file.path()).getParent();
            var references = root.getElementsByTagName("ProjectReference");

            for (int i = 0; i < references.getLength(); i++) {

                var include = ((Element) references.item(i)).getAttribute("Include");
                var relative = include.replace('\\', '/');
                var resolved = (dir == null ? Path.of(relative) : dir.resolve(relative)).normalize().toString();

                if (!expectedProjects.contains(resolved)) {
                    var valid = expectedProjects.stream()
                            .filter(p -> !p.equals(file.path()))
                            .map(p -> (dir == null ? Path.of(p) : dir.relativize(Path.of(p))).toString())
                            .toList();
                    errors.add(file.path() + ": ProjectReference \"" + include + "\" apunta a \"" + resolved
                            + "\", que no es un proyecto del plan. Rutas válidas desde este .csproj: " + valid);
                }
            }
        }

        return errors;
    }

    private static Element parse(String content) throws ParserConfigurationException, SAXException, IOException {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setExpandEntityReferences(false);
        var builder = factory.newDocumentBuilder();
        builder.setErrorHandler(new org.xml.sax.helpers.DefaultHandler());
        return builder.parse(new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8))).getDocumentElement();
    }
}
