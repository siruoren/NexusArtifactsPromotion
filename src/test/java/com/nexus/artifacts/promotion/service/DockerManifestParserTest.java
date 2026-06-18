package com.nexus.artifacts.promotion.service;

import java.io.IOException;

import org.junit.Test;

import com.nexus.artifacts.promotion.service.DockerManifestParser.DockerManifest;
import com.nexus.artifacts.promotion.service.DockerManifestParser.ManifestReference;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link DockerManifestParser}.
 * Covers all supported manifest formats: Docker V2, OCI, Fat Manifest, OCI Index.
 */
public class DockerManifestParserTest {

  // ==================== Docker V2 Schema 2 ====================

  @Test
  public void testParseDockerV2Manifest() throws IOException {
    String json = "{\n"
        + "  \"schemaVersion\": 2,\n"
        + "  \"mediaType\": \"application/vnd.docker.distribution.manifest.v2+json\",\n"
        + "  \"config\": {\n"
        + "    \"mediaType\": \"application/vnd.docker.container.image.v1+json\",\n"
        + "    \"size\": 7023,\n"
        + "    \"digest\": \"sha256:abc123config\"\n"
        + "  },\n"
        + "  \"layers\": [\n"
        + "    {\"mediaType\": \"application/vnd.docker.image.rootfs.diff.tar.gzip\", \"size\": 32654, \"digest\": \"sha256:layer1\"},\n"
        + "    {\"mediaType\": \"application/vnd.docker.image.rootfs.diff.tar.gzip\", \"size\": 16724, \"digest\": \"sha256:layer2\"},\n"
        + "    {\"mediaType\": \"application/vnd.docker.image.rootfs.diff.tar.gzip\", \"size\": 73109, \"digest\": \"sha256:layer3\"}\n"
        + "  ]\n"
        + "}";

    DockerManifest result = DockerManifestParser.parse(json, DockerManifestParser.MEDIA_TYPE_DOCKER_V2);

    assertFalse(result.isFatManifest());
    assertEquals(DockerManifestParser.MEDIA_TYPE_DOCKER_V2, result.getMediaType());
    assertEquals("sha256:abc123config", result.getConfigDigest());
    assertEquals(3, result.getLayerDigests().size());
    assertEquals("sha256:layer1", result.getLayerDigests().get(0));
    assertEquals("sha256:layer2", result.getLayerDigests().get(1));
    assertEquals("sha256:layer3", result.getLayerDigests().get(2));
  }

  @Test
  public void testParseDockerV2AllBlobDigests() throws IOException {
    String json = "{\n"
        + "  \"schemaVersion\": 2,\n"
        + "  \"mediaType\": \"application/vnd.docker.distribution.manifest.v2+json\",\n"
        + "  \"config\": {\"digest\": \"sha256:config1\"},\n"
        + "  \"layers\": [\n"
        + "    {\"digest\": \"sha256:layer1\"},\n"
        + "    {\"digest\": \"sha256:layer2\"}\n"
        + "  ]\n"
        + "}";

    DockerManifest result = DockerManifestParser.parse(json, DockerManifestParser.MEDIA_TYPE_DOCKER_V2);

    assertEquals(3, result.getAllBlobDigests().size());
    assertTrue(result.getAllBlobDigests().contains("sha256:config1"));
    assertTrue(result.getAllBlobDigests().contains("sha256:layer1"));
    assertTrue(result.getAllBlobDigests().contains("sha256:layer2"));
  }

  // ==================== OCI Image Manifest ====================

  @Test
  public void testParseOCIManifest() throws IOException {
    String json = "{\n"
        + "  \"schemaVersion\": 2,\n"
        + "  \"mediaType\": \"application/vnd.oci.image.manifest.v1+json\",\n"
        + "  \"config\": {\n"
        + "    \"mediaType\": \"application/vnd.oci.image.config.v1+json\",\n"
        + "    \"size\": 7023,\n"
        + "    \"digest\": \"sha256:ociConfig1\"\n"
        + "  },\n"
        + "  \"layers\": [\n"
        + "    {\"mediaType\": \"application/vnd.oci.image.layer.v1.tar+gzip\", \"size\": 32654, \"digest\": \"sha256:ociLayer1\"},\n"
        + "    {\"mediaType\": \"application/vnd.oci.image.layer.v1.tar\", \"size\": 16724, \"digest\": \"sha256:ociLayer2\"}\n"
        + "  ]\n"
        + "}";

    DockerManifest result = DockerManifestParser.parse(json, DockerManifestParser.MEDIA_TYPE_OCI_MANIFEST);

    assertFalse(result.isFatManifest());
    assertEquals(DockerManifestParser.MEDIA_TYPE_OCI_MANIFEST, result.getMediaType());
    assertEquals("sha256:ociConfig1", result.getConfigDigest());
    assertEquals(2, result.getLayerDigests().size());
    assertEquals("sha256:ociLayer1", result.getLayerDigests().get(0));
    assertEquals("sha256:ociLayer2", result.getLayerDigests().get(1));
  }

  // ==================== Docker Fat Manifest (Manifest List) ====================

  @Test
  public void testParseFatManifest() throws IOException {
    String json = "{\n"
        + "  \"schemaVersion\": 2,\n"
        + "  \"mediaType\": \"application/vnd.docker.distribution.manifest.list.v2+json\",\n"
        + "  \"manifests\": [\n"
        + "    {\n"
        + "      \"mediaType\": \"application/vnd.docker.distribution.manifest.v2+json\",\n"
        + "      \"size\": 7143,\n"
        + "      \"digest\": \"sha256:amd64manifest\",\n"
        + "      \"platform\": {\"architecture\": \"amd64\", \"os\": \"linux\"}\n"
        + "    },\n"
        + "    {\n"
        + "      \"mediaType\": \"application/vnd.docker.distribution.manifest.v2+json\",\n"
        + "      \"size\": 7143,\n"
        + "      \"digest\": \"sha256:arm64manifest\",\n"
        + "      \"platform\": {\"architecture\": \"arm64\", \"os\": \"linux\", \"variant\": \"v8\"}\n"
        + "    },\n"
        + "    {\n"
        + "      \"mediaType\": \"application/vnd.docker.distribution.manifest.v2+json\",\n"
        + "      \"size\": 7143,\n"
        + "      \"digest\": \"sha256:arm32manifest\",\n"
        + "      \"platform\": {\"architecture\": \"arm\", \"os\": \"linux\", \"variant\": \"v7\"}\n"
        + "    }\n"
        + "  ]\n"
        + "}";

    DockerManifest result = DockerManifestParser.parse(json, DockerManifestParser.MEDIA_TYPE_FAT_MANIFEST);

    assertTrue(result.isFatManifest());
    assertEquals(DockerManifestParser.MEDIA_TYPE_FAT_MANIFEST, result.getMediaType());
    assertEquals(3, result.getManifestReferences().size());

    // Check amd64 platform
    ManifestReference amd64 = result.getManifestReferences().get(0);
    assertEquals("sha256:amd64manifest", amd64.getDigest());
    assertEquals("linux", amd64.getPlatformOs());
    assertEquals("amd64", amd64.getPlatformArch());
    assertNull(amd64.getPlatformVariant());

    // Check arm64 platform
    ManifestReference arm64 = result.getManifestReferences().get(1);
    assertEquals("sha256:arm64manifest", arm64.getDigest());
    assertEquals("linux", arm64.getPlatformOs());
    assertEquals("arm64", arm64.getPlatformArch());
    assertEquals("v8", arm64.getPlatformVariant());

    // Check arm32 platform
    ManifestReference arm32 = result.getManifestReferences().get(2);
    assertEquals("sha256:arm32manifest", arm32.getDigest());
    assertEquals("arm", arm32.getPlatformArch());
    assertEquals("v7", arm32.getPlatformVariant());
  }

  @Test
  public void testFatManifestSubManifestDigests() throws IOException {
    String json = "{\n"
        + "  \"schemaVersion\": 2,\n"
        + "  \"mediaType\": \"application/vnd.docker.distribution.manifest.list.v2+json\",\n"
        + "  \"manifests\": [\n"
        + "    {\"digest\": \"sha256:sub1\", \"platform\": {\"architecture\": \"amd64\", \"os\": \"linux\"}},\n"
        + "    {\"digest\": \"sha256:sub2\", \"platform\": {\"architecture\": \"arm64\", \"os\": \"linux\"}}\n"
        + "  ]\n"
        + "}";

    DockerManifest result = DockerManifestParser.parse(json, DockerManifestParser.MEDIA_TYPE_FAT_MANIFEST);

    assertEquals(2, result.getSubManifestDigests().size());
    assertTrue(result.getSubManifestDigests().contains("sha256:sub1"));
    assertTrue(result.getSubManifestDigests().contains("sha256:sub2"));
  }

  // ==================== OCI Image Index ====================

  @Test
  public void testParseOCIIndex() throws IOException {
    String json = "{\n"
        + "  \"schemaVersion\": 2,\n"
        + "  \"mediaType\": \"application/vnd.oci.image.index.v1+json\",\n"
        + "  \"manifests\": [\n"
        + "    {\n"
        + "      \"mediaType\": \"application/vnd.oci.image.manifest.v1+json\",\n"
        + "      \"size\": 7143,\n"
        + "      \"digest\": \"sha256:ociAmd64\",\n"
        + "      \"platform\": {\"architecture\": \"amd64\", \"os\": \"linux\"}\n"
        + "    },\n"
        + "    {\n"
        + "      \"mediaType\": \"application/vnd.oci.image.manifest.v1+json\",\n"
        + "      \"size\": 7143,\n"
        + "      \"digest\": \"sha256:ociArm64\",\n"
        + "      \"platform\": {\"architecture\": \"arm64\", \"os\": \"linux\"}\n"
        + "    }\n"
        + "  ]\n"
        + "}";

    DockerManifest result = DockerManifestParser.parse(json, DockerManifestParser.MEDIA_TYPE_OCI_INDEX);

    assertTrue(result.isFatManifest());
    assertEquals(DockerManifestParser.MEDIA_TYPE_OCI_INDEX, result.getMediaType());
    assertEquals(2, result.getManifestReferences().size());
    assertEquals("sha256:ociAmd64", result.getManifestReferences().get(0).getDigest());
    assertEquals("sha256:ociArm64", result.getManifestReferences().get(1).getDigest());
  }

  // ==================== Auto-detection ====================

  @Test
  public void testAutoDetectDockerV2() throws IOException {
    String json = "{\n"
        + "  \"schemaVersion\": 2,\n"
        + "  \"mediaType\": \"application/vnd.docker.distribution.manifest.v2+json\",\n"
        + "  \"config\": {\"digest\": \"sha256:autoConfig\"},\n"
        + "  \"layers\": [{\"digest\": \"sha256:autoLayer\"}]\n"
        + "}";

    DockerManifest result = DockerManifestParser.parse(json, null);
    assertFalse(result.isFatManifest());
    assertEquals("sha256:autoConfig", result.getConfigDigest());
  }

  @Test
  public void testAutoDetectFatManifest() throws IOException {
    String json = "{\n"
        + "  \"schemaVersion\": 2,\n"
        + "  \"mediaType\": \"application/vnd.docker.distribution.manifest.list.v2+json\",\n"
        + "  \"manifests\": [\n"
        + "    {\"digest\": \"sha256:detected1\", \"platform\": {\"architecture\": \"amd64\", \"os\": \"linux\"}}\n"
        + "  ]\n"
        + "}";

    DockerManifest result = DockerManifestParser.parse(json, null);
    assertTrue(result.isFatManifest());
    assertEquals(1, result.getManifestReferences().size());
  }

  @Test
  public void testAutoDetectOCIManifest() throws IOException {
    String json = "{\n"
        + "  \"schemaVersion\": 2,\n"
        + "  \"mediaType\": \"application/vnd.oci.image.manifest.v1+json\",\n"
        + "  \"config\": {\"digest\": \"sha256:ociAutoConfig\"},\n"
        + "  \"layers\": [{\"digest\": \"sha256:ociAutoLayer\"}]\n"
        + "}";

    DockerManifest result = DockerManifestParser.parse(json, null);
    assertFalse(result.isFatManifest());
    assertEquals("sha256:ociAutoConfig", result.getConfigDigest());
  }

  @Test
  public void testAutoDetectOCIIndex() throws IOException {
    String json = "{\n"
        + "  \"schemaVersion\": 2,\n"
        + "  \"mediaType\": \"application/vnd.oci.image.index.v1+json\",\n"
        + "  \"manifests\": [\n"
        + "    {\"digest\": \"sha256:ociIdx1\", \"platform\": {\"architecture\": \"amd64\", \"os\": \"linux\"}}\n"
        + "  ]\n"
        + "}";

    DockerManifest result = DockerManifestParser.parse(json, null);
    assertTrue(result.isFatManifest());
  }

  // ==================== Edge cases ====================

  @Test(expected = IOException.class)
  public void testParseEmptyManifest() throws IOException {
    DockerManifestParser.parse("", null);
  }

  @Test(expected = IOException.class)
  public void testParseNullManifest() throws IOException {
    DockerManifestParser.parse(null, null);
  }

  @Test
  public void testParseManifestWithNoLayers() throws IOException {
    String json = "{\n"
        + "  \"schemaVersion\": 2,\n"
        + "  \"mediaType\": \"application/vnd.docker.distribution.manifest.v2+json\",\n"
        + "  \"config\": {\"digest\": \"sha256:configNoLayers\"},\n"
        + "  \"layers\": []\n"
        + "}";

    DockerManifest result = DockerManifestParser.parse(json, DockerManifestParser.MEDIA_TYPE_DOCKER_V2);
    assertEquals("sha256:configNoLayers", result.getConfigDigest());
    assertEquals(0, result.getLayerDigests().size());
    assertEquals(1, result.getAllBlobDigests().size());
  }

  @Test
  public void testParseManifestWithNoConfig() throws IOException {
    String json = "{\n"
        + "  \"schemaVersion\": 2,\n"
        + "  \"mediaType\": \"application/vnd.docker.distribution.manifest.v2+json\",\n"
        + "  \"layers\": [{\"digest\": \"sha256:onlyLayer\"}]\n"
        + "}";

    DockerManifest result = DockerManifestParser.parse(json, DockerManifestParser.MEDIA_TYPE_DOCKER_V2);
    assertNull(result.getConfigDigest());
    assertEquals(1, result.getLayerDigests().size());
  }

  @Test
  public void testParseFatManifestWithMissingPlatform() throws IOException {
    String json = "{\n"
        + "  \"schemaVersion\": 2,\n"
        + "  \"mediaType\": \"application/vnd.docker.distribution.manifest.list.v2+json\",\n"
        + "  \"manifests\": [\n"
        + "    {\"digest\": \"sha256:noPlatform\", \"size\": 100}\n"
        + "  ]\n"
        + "}";

    DockerManifest result = DockerManifestParser.parse(json, DockerManifestParser.MEDIA_TYPE_FAT_MANIFEST);
    assertTrue(result.isFatManifest());
    assertEquals(1, result.getManifestReferences().size());
    assertEquals("sha256:noPlatform", result.getManifestReferences().get(0).getDigest());
    assertNull(result.getManifestReferences().get(0).getPlatformOs());
    assertNull(result.getManifestReferences().get(0).getPlatformArch());
  }

  @Test
  public void testManifestReferenceToString() {
    ManifestReference ref = new ManifestReference("sha256:abc123");
    ref.setPlatformOs("linux");
    ref.setPlatformArch("amd64");
    String str = ref.toString();
    assertTrue(str.contains("sha256:abc123"));
    assertTrue(str.contains("linux"));
    assertTrue(str.contains("amd64"));
  }

  @Test
  public void testManifestReferenceToStringWithVariant() {
    ManifestReference ref = new ManifestReference("sha256:abc123");
    ref.setPlatformOs("linux");
    ref.setPlatformArch("arm64");
    ref.setPlatformVariant("v8");
    String str = ref.toString();
    assertTrue(str.contains("v8"));
  }

  @Test
  public void testUnknownMediaTypeFallsBackToSingleManifest() throws IOException {
    String json = "{\n"
        + "  \"schemaVersion\": 2,\n"
        + "  \"mediaType\": \"application/vnd.unknown.manifest.v1+json\",\n"
        + "  \"config\": {\"digest\": \"sha256:unknownConfig\"},\n"
        + "  \"layers\": [{\"digest\": \"sha256:unknownLayer\"}]\n"
        + "}";

    DockerManifest result = DockerManifestParser.parse(json, "application/vnd.unknown.manifest.v1+json");
    assertFalse(result.isFatManifest());
    assertEquals("sha256:unknownConfig", result.getConfigDigest());
  }

  @Test
  public void testV1SignedManifest() throws IOException {
    String json = "{\n"
        + "  \"schemaVersion\": 1,\n"
        + "  \"mediaType\": \"application/vnd.docker.distribution.manifest.v1+prettyjws\"\n"
        + "}";

    DockerManifest result = DockerManifestParser.parse(json, DockerManifestParser.MEDIA_TYPE_DOCKER_V1_SIGNED);
    assertFalse(result.isFatManifest());
    assertEquals(DockerManifestParser.MEDIA_TYPE_DOCKER_V1_SIGNED, result.getMediaType());
  }

  @Test
  public void testFatManifestHeuristicWithoutMediaType() throws IOException {
    // No mediaType in JSON, but has "manifests" array and no "config" → should detect as fat manifest
    String json = "{\n"
        + "  \"schemaVersion\": 2,\n"
        + "  \"manifests\": [\n"
        + "    {\"digest\": \"sha256:heuristic1\", \"platform\": {\"architecture\": \"amd64\", \"os\": \"linux\"}}\n"
        + "  ]\n"
        + "}";

    DockerManifest result = DockerManifestParser.parse(json, null);
    assertTrue(result.isFatManifest());
  }

  @Test
  public void testManifestWithSpecialCharsInDigest() throws IOException {
    String json = "{\n"
        + "  \"schemaVersion\": 2,\n"
        + "  \"mediaType\": \"application/vnd.docker.distribution.manifest.v2+json\",\n"
        + "  \"config\": {\"digest\": \"sha256:a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0c1d2e3f4a5b6c7d8e9f0a1b2\"},\n"
        + "  \"layers\": [{\"digest\": \"sha256:f1e2d3c4b5a6978890011223344556677889900aabbccddeeff001122334455\"}]\n"
        + "}";

    DockerManifest result = DockerManifestParser.parse(json, DockerManifestParser.MEDIA_TYPE_DOCKER_V2);
    assertEquals("sha256:a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0c1d2e3f4a5b6c7d8e9f0a1b2", result.getConfigDigest());
    assertEquals("sha256:f1e2d3c4b5a6978890011223344556677889900aabbccddeeff001122334455", result.getLayerDigests().get(0));
  }
}
