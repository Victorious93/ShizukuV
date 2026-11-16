package ai.victorious.bridge.core

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * docs/contract/bridge-contract-v1.json is copied verbatim into the DroidCommand-AI, File-Manager and VictorSuite
 * integrations, whose tests check *their* catalogues against it. This test checks the service against the same file,
 * so a change to operations, capabilities, error codes or identity here fails until the contract is revised.
 */
class ContractFixtureTest {
    private val fixture = JSONObject(generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle").exists() }.resolve("docs/contract/bridge-contract-v1.json").readText())

    @Test fun identityAndVersionMatch() {
        assertEquals(BridgeIdentity.APPLICATION_ID, fixture.getString("application_id"))
        assertEquals(BridgeIdentity.PROVIDER_AUTHORITY_SUFFIX, fixture.getString("provider_authority_suffix"))
        assertEquals(BridgeIdentity.META_CLIENT_SUPPORT, fixture.getString("client_support_meta_data"))
        assertEquals(BridgeIdentity.BRIDGE_API_VERSION, fixture.getInt("bridge_api_version"))
    }

    @Test fun operationCatalogueMatchesExactly() {
        val ops = fixture.getJSONObject("operations")
        assertEquals(Operation.values().map { it.id }.toSet(), ops.keySet())
        Operation.values().forEach { assertEquals(it.id, it.capability.id, ops.getString(it.id)) }
    }

    @Test fun errorCodesMatchExactly() {
        val a = fixture.getJSONArray("error_codes")
        assertEquals(ErrorCode.values().map { it.name }, (0 until a.length()).map { a.getString(it) })
    }

    @Test fun backendRequirementsMatch() {
        val a = fixture.getJSONArray("backend_requirements")
        assertEquals(BackendRequirement.values().map { it.id }, (0 until a.length()).map { a.getString(it) })
    }
}
