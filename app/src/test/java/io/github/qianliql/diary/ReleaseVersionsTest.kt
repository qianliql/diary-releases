package io.github.qianliql.diary
import io.github.qianliql.diary.model.ReleaseVersions
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ReleaseVersionsTest {
    private fun release(tag: String, draft: Boolean = false, url: String = "https://github.com/qianliql/diary-releases/releases/tag/$tag") = JSONObject().put("tag_name",tag).put("draft",draft).put("prerelease",true).put("html_url",url)
    @Test fun findsPreviewAppVersionAndSkipsModelRelease() {
        val items = JSONArray().put(release("models-v1")).put(release("v0.0.0")).put(release("v0.0.1"))
        assertTrue(ReleaseVersions.newer(items,1)!!.endsWith("v0.0.1")); assertNull(ReleaseVersions.newer(items,2))
    }
    @Test fun selectsHighestVersionAndRejectsDraftAndForeignUrl() {
        val items = JSONArray().put(release("v1.0.0",true)).put(release("v0.0.9",url="https://example.com/x")).put(release("v0.1.0")).put(release("v0.0.2"))
        assertTrue(ReleaseVersions.newer(items,1)!!.endsWith("v0.1.0"))
    }
    @Test fun rejectsAmbiguousAndOverflowingVersions() {
        val items = JSONArray().put(release("v0.0.100")).put(release("v99999999999999999.0.1")).put(release("v0.0.3-beta"))
        assertNull(ReleaseVersions.newer(items,1))
    }
}
