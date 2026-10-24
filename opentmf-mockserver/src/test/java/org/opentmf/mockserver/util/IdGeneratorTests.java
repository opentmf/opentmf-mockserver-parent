package org.opentmf.mockserver.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.opentmf.mockserver.util.Constants.ID_FORMATS;
import static org.opentmf.mockserver.util.Constants.ID_FORMAT_DEFAULT;

import io.hypersistence.tsid.TSID;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import uk.org.webcompere.systemstubs.environment.EnvironmentVariables;
import uk.org.webcompere.systemstubs.jupiter.SystemStub;
import uk.org.webcompere.systemstubs.jupiter.SystemStubsExtension;

@ExtendWith(SystemStubsExtension.class)
class IdGeneratorTests {

  private static final String URN_PREFIX = "urn:example-tmf:common:document-id:";

  @SystemStub private EnvironmentVariables environmentVariables;

  @Test
  void generate_withoutAnySetting_returnsBareTsid() {
    environmentVariables.remove(ID_FORMATS).remove(ID_FORMAT_DEFAULT);

    String id = IdGenerator.generate("document");

    assertEquals(13, id.length(), id);
    assertTrue(TSID.isValid(id), id);
  }

  @ParameterizedTest
  @ValueSource(strings = {"uuid", "UUID", "{uuid}"})
  void generate_withUuidDefault_returnsUuid(String value) {
    environmentVariables.set(ID_FORMAT_DEFAULT, value);

    String id = IdGenerator.generate("document");

    assertEquals(id, UUID.fromString(id).toString());
  }

  @Test
  void generate_withTemplateDefault_appliesItToEveryDomainWithoutEntry() {
    environmentVariables
        .set(ID_FORMAT_DEFAULT, "res-{tsidNumber}")
        .set(ID_FORMATS, "document=" + URN_PREFIX + "{tsidNumber}");

    assertTrue(IdGenerator.generate("serviceOrder").matches("res-\\d+"));
    assertTrue(IdGenerator.generate("document").startsWith(URN_PREFIX));
  }

  @Test
  void generate_withUuidEntry_returnsUuidForThatDomainOnly() {
    environmentVariables.set(ID_FORMATS, "document=uuid,party=p-{uuid}");

    String id = IdGenerator.generate("document");
    String partyId = IdGenerator.generate("party");

    assertEquals(id, UUID.fromString(id).toString());
    assertTrue(partyId.startsWith("p-"), partyId);
    UUID.fromString(partyId.substring(2));
    assertTrue(TSID.isValid(IdGenerator.generate("other")));
  }

  @Test
  void generate_withMalformedDefault_failsLoudlyEvenForADomainWithItsOwnEntry() {
    environmentVariables
        .set(ID_FORMAT_DEFAULT, "uuids")
        .set(ID_FORMATS, "document=" + URN_PREFIX + "{tsidNumber}");

    InvalidIdFormatException e =
        assertThrows(InvalidIdFormatException.class, () -> IdGenerator.generate("document"));

    assertTrue(e.getMessage().startsWith("Invalid ID_FORMAT_DEFAULT [uuids]"), e.getMessage());
  }

  @ParameterizedTest
  @ValueSource(strings = {"0", "5", "1023"})
  void generate_withValidTsidNode_succeeds(String node) {
    environmentVariables.set("TSID_NODE", node);

    assertTrue(TSID.isValid(IdGenerator.generate("document")));
  }

  @Test
  void generate_withTsidNodeInsideConfiguredNodeCount_succeeds() {
    environmentVariables.set("TSID_NODE", "4000").set("TSID_NODE_COUNT", "4096");

    assertTrue(TSID.isValid(IdGenerator.generate("document")));
  }

  @ParameterizedTest
  @ValueSource(strings = {"-1", "1024", "1500", "abc", " 5", "0x10"})
  void generate_withInvalidTsidNode_failsLoudly(String node) {
    environmentVariables.set("TSID_NODE", node);

    InvalidIdFormatException e =
        assertThrows(InvalidIdFormatException.class, () -> IdGenerator.generate("document"));

    assertTrue(e.getMessage().startsWith("Invalid TSID node ["), e.getMessage());
  }

  @ParameterizedTest
  @ValueSource(strings = {"0", "abc", "1048577"})
  void generate_withInvalidTsidNodeCount_failsLoudly(String count) {
    environmentVariables.set("TSID_NODE", "1").set("TSID_NODE_COUNT", count);

    InvalidIdFormatException e =
        assertThrows(InvalidIdFormatException.class, () -> IdGenerator.generate("document"));

    assertTrue(e.getMessage().startsWith("Invalid TSID node count ["), e.getMessage());
  }

  @Test
  void generate_withTsidNodeAboveConfiguredNodeCount_failsLoudly() {
    environmentVariables.set("TSID_NODE", "300").set("TSID_NODE_COUNT", "256");

    assertThrows(InvalidIdFormatException.class, () -> IdGenerator.generate("document"));
  }

  @Test
  void generate_forDomainWithoutEntry_returnsBareTsid() {
    environmentVariables.set(ID_FORMATS, "document=" + URN_PREFIX + "{tsidNumber}");

    String id = IdGenerator.generate("tmf-api/serviceOrdering/v4/serviceOrder");

    assertTrue(TSID.isValid(id), id);
  }

  @Test
  void generate_withTsidNumberPlaceholder_returnsPrefixAndNumericTsid() {
    environmentVariables.set(ID_FORMATS, "document=" + URN_PREFIX + "{tsidNumber}");

    String id = IdGenerator.generate("document");

    assertTrue(id.startsWith(URN_PREFIX), id);
    String number = id.substring(URN_PREFIX.length());
    assertTrue(number.matches("\\d+"), id);
    assertTrue(TSID.isValid(TSID.from(Long.parseLong(number)).toString()), id);
  }

  @Test
  void generate_withTsidPlaceholder_returnsPrefixAndTsidString() {
    environmentVariables.set(ID_FORMATS, "document=doc-{tsid}-x");

    String id = IdGenerator.generate("document");

    assertTrue(id.startsWith("doc-") && id.endsWith("-x"), id);
    assertTrue(TSID.isValid(id.substring(4, id.length() - 2)), id);
  }

  @Test
  void generate_twice_returnsDistinctIds() {
    environmentVariables.set(ID_FORMATS, "document=" + URN_PREFIX + "{tsidNumber}");

    assertNotEquals(IdGenerator.generate("document"), IdGenerator.generate("document"));
  }

  @Test
  void parse_withSeveralEntries_normalizesDomainsAndSkipsBlankEntries() {
    Map<String, String> formats =
        IdGenerator.parse(
            " /document/ = " + URN_PREFIX + "{tsidNumber} ,, tmf-api/x/v4/thing=t-{tsid}");

    assertEquals(
        Map.of("document", URN_PREFIX + "{tsidNumber}", "tmf-api/x/v4/thing", "t-{tsid}"),
        formats);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "document",
        "=urn:x:{tsid}",
        "document=urn:x:",
        "document=urn:{tsid}:{tsidNumber}",
        "document=urn:{tsid}:{tsid}",
        "document=urn:{uuid}:{tsid}",
        "document=uuid-x",
        "document=urn:x:{tsidnumber}{tsid}",
        "document=urn:x/{tsid}",
        "document=urn:x?{tsid}",
        "document=urn:x#{tsid}",
        "document=urn x:{tsid}",
        "document={tsid}:(version=1)",
        "document=a-{tsid},document=b-{tsid}"
      })
  void generate_withMalformedIdFormats_failsLoudly(String value) {
    environmentVariables.set(ID_FORMATS, value);

    InvalidIdFormatException e =
        assertThrows(InvalidIdFormatException.class, () -> IdGenerator.generate("other"));

    assertTrue(e.getMessage().startsWith("Invalid ID_FORMATS entry ["), e.getMessage());
  }
}
