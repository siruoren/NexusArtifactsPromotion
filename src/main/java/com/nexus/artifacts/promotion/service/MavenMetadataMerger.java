package com.nexus.artifacts.promotion.service;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

/**
 * Smart merge utility for Maven metadata XML files.
 *
 * <p>When promoting Maven artifacts, the target repository may already have
 * a maven-metadata.xml for the same group/artifact. Simply overwriting it
 * would lose existing version entries. This utility merges source and target
 * metadata to preserve all version information.
 *
 * <p>Merge strategy:
 * <ul>
 *   <li><b>versioning/versions</b>: Union of all version entries from both source and target</li>
 *   <li><b>versioning/latest</b>: Take the highest version string (semver-like comparison)</li>
 *   <li><b>versioning/release</b>: Take the highest non-SNAPSHOT version</li>
 *   <li><b>versioning/lastUpdated</b>: Take the most recent timestamp</li>
 *   <li><b>versioning/snapshotVersions</b>: Union by classifier+extension key, prefer newer timestamp</li>
 *   <li>Other elements: prefer source metadata if present, otherwise keep target</li>
 * </ul>
 */
public class MavenMetadataMerger {

  private static final Logger log = LoggerFactory.getLogger(MavenMetadataMerger.class);

  /**
   * Merge source and target maven-metadata.xml content.
   *
   * @param sourceContent the source repository's maven-metadata.xml content
   * @param targetContent the target repository's maven-metadata.xml content (may be null if not exists)
   * @return the merged maven-metadata.xml content
   */
  public static String merge(String sourceContent, String targetContent) {
    if (targetContent == null || targetContent.trim().isEmpty()) {
      log.debug("No target metadata to merge, using source as-is");
      return sourceContent;
    }
    if (sourceContent == null || sourceContent.trim().isEmpty()) {
      log.debug("No source metadata to merge, using target as-is");
      return targetContent;
    }

    try {
      DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
      // Disable external entity processing for security
      factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
      factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
      factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);

      DocumentBuilder builder = factory.newDocumentBuilder();

      Document sourceDoc = builder.parse(new InputSource(new StringReader(sourceContent)));
      Document targetDoc = builder.parse(new InputSource(new StringReader(targetContent)));

      Document merged = mergeDocuments(sourceDoc, targetDoc);
      return documentToString(merged);
    }
    catch (Exception e) {
      log.warn("Failed to merge maven-metadata.xml, falling back to source content: {}", e.getMessage());
      return sourceContent;
    }
  }

  /**
   * Check if a file path is a maven-metadata.xml file.
   */
  public static boolean isMavenMetadata(String path) {
    return path != null && path.endsWith("maven-metadata.xml");
  }

  /**
   * Check if a file path is a maven-metadata.xml checksum file.
   */
  public static boolean isMavenMetadataChecksum(String path) {
    return path != null &&
        (path.endsWith("maven-metadata.xml.md5") ||
            path.endsWith("maven-metadata.xml.sha1") ||
            path.endsWith("maven-metadata.xml.sha256") ||
            path.endsWith("maven-metadata.xml.sha512"));
  }

  private static Document mergeDocuments(Document source, Document target) throws Exception {
    // Use source as the base document
    Document merged = (Document) source.cloneNode(true);

    Element sourceRoot = source.getDocumentElement();
    Element targetRoot = target.getDocumentElement();

    // Merge versioning section
    Element sourceVersioning = getChildElement(sourceRoot, "versioning");
    Element targetVersioning = getChildElement(targetRoot, "versioning");

    if (sourceVersioning != null && targetVersioning != null) {
      Element mergedVersioning = getChildElement(merged.getDocumentElement(), "versioning");
      if (mergedVersioning != null) {
        mergeVersioning(merged, mergedVersioning, sourceVersioning, targetVersioning);
      }
    }
    else if (targetVersioning != null && sourceVersioning == null) {
      // Source has no versioning — import target's
      Node imported = merged.importNode(targetVersioning, true);
      merged.getDocumentElement().appendChild(imported);
    }

    return merged;
  }

  private static void mergeVersioning(Document mergedDoc, Element mergedVersioning,
                                       Element sourceVersioning, Element targetVersioning) {
    // 1. Merge versions — union of all version entries
    mergeVersions(mergedDoc, mergedVersioning, sourceVersioning, targetVersioning);

    // 2. Merge latest — take highest version
    mergeLatest(mergedDoc, mergedVersioning, sourceVersioning, targetVersioning);

    // 3. Merge release — take highest non-SNAPSHOT version
    mergeRelease(mergedDoc, mergedVersioning, sourceVersioning, targetVersioning);

    // 4. Merge lastUpdated — take most recent
    mergeLastUpdated(mergedDoc, mergedVersioning, sourceVersioning, targetVersioning);

    // 5. Merge snapshotVersions — union by key
    mergeSnapshotVersions(mergedDoc, mergedVersioning, sourceVersioning, targetVersioning);
  }

  private static void mergeVersions(Document mergedDoc, Element mergedVersioning,
                                     Element sourceVersioning, Element targetVersioning) {
    // Collect all unique versions
    Set<String> allVersions = new HashSet<>();

    // Get versions from source
    Element sourceVersions = getChildElement(sourceVersioning, "versions");
    if (sourceVersions != null) {
      allVersions.addAll(getVersionStrings(sourceVersions));
    }

    // Get versions from target
    Element targetVersions = getChildElement(targetVersioning, "versions");
    if (targetVersions != null) {
      allVersions.addAll(getVersionStrings(targetVersions));
    }

    if (allVersions.isEmpty()) return;

    // Remove existing versions element from merged
    Element existingVersions = getChildElement(mergedVersioning, "versions");
    if (existingVersions != null) {
      mergedVersioning.removeChild(existingVersions);
    }

    // Create new versions element
    Element newVersions = mergedDoc.createElement("versions");
    List<String> sortedVersions = new ArrayList<>(allVersions);
    sortedVersions.sort(MavenMetadataMerger::compareVersions);
    for (String version : sortedVersions) {
      Element versionEl = mergedDoc.createElement("version");
      versionEl.setTextContent(version);
      newVersions.appendChild(versionEl);
    }
    mergedVersioning.appendChild(newVersions);
  }

  private static void mergeLatest(Document mergedDoc, Element mergedVersioning,
                                   Element sourceVersioning, Element targetVersioning) {
    String sourceLatest = getElementText(sourceVersioning, "latest");
    String targetLatest = getElementText(targetVersioning, "latest");

    String mergedLatest = pickHigherVersion(sourceLatest, targetLatest);
    if (mergedLatest != null) {
      setElementText(mergedDoc, mergedVersioning, "latest", mergedLatest);
    }
  }

  private static void mergeRelease(Document mergedDoc, Element mergedVersioning,
                                    Element sourceVersioning, Element targetVersioning) {
    String sourceRelease = getElementText(sourceVersioning, "release");
    String targetRelease = getElementText(targetVersioning, "release");

    // Pick higher non-SNAPSHOT version
    String mergedRelease = pickHigherVersion(sourceRelease, targetRelease);
    if (mergedRelease != null && !mergedRelease.endsWith("-SNAPSHOT")) {
      setElementText(mergedDoc, mergedVersioning, "release", mergedRelease);
    }
  }

  private static void mergeLastUpdated(Document mergedDoc, Element mergedVersioning,
                                         Element sourceVersioning, Element targetVersioning) {
    String sourceTs = getElementText(sourceVersioning, "lastUpdated");
    String targetTs = getElementText(targetVersioning, "lastUpdated");

    // Take the most recent timestamp (lexicographic comparison works for YYYYMMDDHHmmss)
    String mergedTs = sourceTs;
    if (targetTs != null && (mergedTs == null || targetTs.compareTo(mergedTs) > 0)) {
      mergedTs = targetTs;
    }
    if (mergedTs != null) {
      setElementText(mergedDoc, mergedVersioning, "lastUpdated", mergedTs);
    }
  }

  private static void mergeSnapshotVersions(Document mergedDoc, Element mergedVersioning,
                                              Element sourceVersioning, Element targetVersioning) {
    // Simple strategy: keep source's snapshotVersions, add any from target that don't conflict
    // A conflict is when both have the same classifier+extension combination
    Element sourceSv = getChildElement(sourceVersioning, "snapshotVersions");
    Element targetSv = getChildElement(targetVersioning, "snapshotVersions");

    if (sourceSv == null && targetSv == null) return;

    // Remove existing snapshotVersions from merged
    Element existingSv = getChildElement(mergedVersioning, "snapshotVersions");
    if (existingSv != null) {
      mergedVersioning.removeChild(existingSv);
    }

    Element newSv = mergedDoc.createElement("snapshotVersions");

    // Collect source entries
    Set<String> sourceKeys = new HashSet<>();
    if (sourceSv != null) {
      NodeList items = sourceSv.getElementsByTagName("snapshotVersion");
      for (int i = 0; i < items.getLength(); i++) {
        Element item = (Element) items.item(i);
        String key = getSnapshotVersionKey(item);
        sourceKeys.add(key);
        Node imported = mergedDoc.importNode(item, true);
        newSv.appendChild(imported);
      }
    }

    // Add target entries that don't conflict
    if (targetSv != null) {
      NodeList items = targetSv.getElementsByTagName("snapshotVersion");
      for (int i = 0; i < items.getLength(); i++) {
        Element item = (Element) items.item(i);
        String key = getSnapshotVersionKey(item);
        if (!sourceKeys.contains(key)) {
          Node imported = mergedDoc.importNode(item, true);
          newSv.appendChild(imported);
        }
      }
    }

    mergedVersioning.appendChild(newSv);
  }

  // --- Utility methods ---

  private static Element getChildElement(Element parent, String tagName) {
    NodeList children = parent.getElementsByTagName(tagName);
    for (int i = 0; i < children.getLength(); i++) {
      Node child = children.item(i);
      if (child.getParentNode() == parent) {
        return (Element) child;
      }
    }
    return null;
  }

  private static String getElementText(Element parent, String tagName) {
    Element el = getChildElement(parent, tagName);
    return el != null ? el.getTextContent().trim() : null;
  }

  private static void setElementText(Document doc, Element parent, String tagName, String text) {
    Element el = getChildElement(parent, tagName);
    if (el != null) {
      el.setTextContent(text);
    }
    else {
      el = doc.createElement(tagName);
      el.setTextContent(text);
      parent.appendChild(el);
    }
  }

  private static List<String> getVersionStrings(Element versionsElement) {
    List<String> versions = new ArrayList<>();
    NodeList versionNodes = versionsElement.getElementsByTagName("version");
    for (int i = 0; i < versionNodes.getLength(); i++) {
      String v = versionNodes.item(i).getTextContent().trim();
      if (!v.isEmpty()) {
        versions.add(v);
      }
    }
    return versions;
  }

  private static String getSnapshotVersionKey(Element snapshotVersion) {
    String classifier = getElementText(snapshotVersion, "classifier");
    String extension = getElementText(snapshotVersion, "extension");
    return (classifier != null ? classifier : "") + ":" + (extension != null ? extension : "");
  }

  /**
   * Pick the higher of two version strings using semver-like comparison.
   */
  private static String pickHigherVersion(String v1, String v2) {
    if (v1 == null) return v2;
    if (v2 == null) return v1;
    return compareVersions(v1, v2) >= 0 ? v1 : v2;
  }

  /**
   * Compare two Maven version strings.
   * Handles formats like "1.0", "1.0.0", "1.0-SNAPSHOT", "1.0-alpha1", etc.
   */
  static int compareVersions(String v1, String v2) {
    if (v1 == null && v2 == null) return 0;
    if (v1 == null) return -1;
    if (v2 == null) return 1;

    // Normalize: strip -SNAPSHOT suffix for comparison
    boolean v1Snapshot = v1.endsWith("-SNAPSHOT");
    boolean v2Snapshot = v2.endsWith("-SNAPSHOT");
    String nv1 = v1Snapshot ? v1.substring(0, v1.length() - "-SNAPSHOT".length()) : v1;
    String nv2 = v2Snapshot ? v2.substring(0, v2.length() - "-SNAPSHOT".length()) : v2;

    // Split into parts
    String[] parts1 = nv1.split("[.\\-_]");
    String[] parts2 = nv2.split("[.\\-_]");

    int maxLen = Math.max(parts1.length, parts2.length);
    for (int i = 0; i < maxLen; i++) {
      String p1 = i < parts1.length ? parts1[i] : "0";
      String p2 = i < parts2.length ? parts2[i] : "0";

      // Try numeric comparison
      try {
        int n1 = Integer.parseInt(p1);
        int n2 = Integer.parseInt(p2);
        if (n1 != n2) return Integer.compare(n1, n2);
        continue;
      }
      catch (NumberFormatException ignored) {}

      // Fall back to lexicographic
      int cmp = p1.compareTo(p2);
      if (cmp != 0) return cmp;
    }

    // Same base version — release > snapshot
    if (v1Snapshot != v2Snapshot) {
      return v1Snapshot ? -1 : 1;
    }

    return 0;
  }

  private static String documentToString(Document doc) throws Exception {
    TransformerFactory tf = TransformerFactory.newInstance();
    Transformer transformer = tf.newTransformer();
    transformer.setOutputProperty(OutputKeys.INDENT, "yes");
    transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
    transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");

    StringWriter writer = new StringWriter();
    transformer.transform(new DOMSource(doc), new StreamResult(writer));
    return writer.toString();
  }
}
