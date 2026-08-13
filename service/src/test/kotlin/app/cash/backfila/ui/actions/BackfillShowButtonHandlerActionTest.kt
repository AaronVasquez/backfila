package app.cash.backfila.ui.actions

import app.cash.backfila.BackfilaTestingModule
import app.cash.backfila.service.persistence.BackfillState
import app.cash.backfila.ui.pages.BackfillShowAction.Companion.APPROVE_AND_START_STATE_BUTTON_LABEL
import app.cash.backfila.ui.pages.BackfillShowAction.Companion.APPROVE_AND_START_STATE_VALUE
import app.cash.backfila.ui.pages.BackfillShowAction.Companion.START_STATE_BUTTON_LABEL
import com.google.inject.Module
import jakarta.inject.Inject
import misk.exceptions.BadRequestException
import misk.exceptions.UnauthorizedException
import misk.inject.KAbstractModule
import misk.security.authz.FakeCallerAuthenticator
import misk.security.authz.MiskCallerAuthenticator
import misk.testing.MiskTest
import misk.testing.MiskTestModule
import misk.web.WebActionModule
import misk.web.WebServerTestingModule
import misk.web.WebTestClient
import misk.web.dashboard.DashboardTab
import okhttp3.FormBody
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

@MiskTest(startService = true)
class BackfillShowButtonHandlerActionTest {
  @MiskTestModule
  val module: Module = object : KAbstractModule() {
    override fun configure() {
      install(BackfilaTestingModule(bindMiskCaller = false))
      install(WebServerTestingModule())
      install(WebActionModule.create<BackfillShowButtonHandlerAction>())
      newMultibinder<DashboardTab>()
      multibind<MiskCallerAuthenticator>().to<FakeCallerAuthenticator>()
    }
  }

  @Inject
  lateinit var webTestClient: WebTestClient

  @Test
  fun `paused run gets the appropriate start action`() {
    val approval = getStateButton(BackfillState.PAUSED, approvalRequired = true)!!
    val ordinary = getStateButton(BackfillState.PAUSED, approvalRequired = false)!!
    assertThat(approval.label).isEqualTo(APPROVE_AND_START_STATE_BUTTON_LABEL)
    assertThat(approval.href).isEqualTo(APPROVE_AND_START_STATE_VALUE)
    assertThat(ordinary.label).isEqualTo(START_STATE_BUTTON_LABEL)
  }

  @Test
  fun `only explicit start values are accepted`() {
    assertThat(startBackfillRequest(APPROVE_AND_START_STATE_VALUE).approve).isTrue()
    assertThat(startBackfillRequest(BackfillState.RUNNING.name).approve).isFalse()
    assertThatThrownBy { startBackfillRequest(BackfillState.PAUSED.name) }
      .isInstanceOf(BadRequestException::class.java)
  }

  @Test
  fun `creator cannot approve in the browser`() {
    assertThat(canApproveBackfill("molly", "diana")).isTrue()
    assertThat(canApproveBackfill("molly", "molly")).isFalse()
    assertThat(canApproveBackfill(null, "diana")).isFalse()
    assertThat(canApproveBackfill("molly", null)).isFalse()
  }

  @Test
  fun `browser updates require exact same origin fetch metadata`() {
    assertThatCode { requireSameOriginBrowserRequest("same-origin") }.doesNotThrowAnyException()
    listOf("cross-site", "same-site", "none", null).forEach {
      assertThatThrownBy { requireSameOriginBrowserRequest(it) }
        .isInstanceOf(UnauthorizedException::class.java)
    }
  }

  @Test
  fun `routed start boundary rejects non same origin requests`() {
    listOf("cross-site", "same-site", "none", null).forEach {
      assertThat(postStart(fetchSite = it).response.code).isEqualTo(403)
    }

    val rejectedStart = postStart(fetchSite = "same-origin").response
    assertThat(rejectedStart.code).isEqualTo(422)
    assertThat(rejectedStart.body.string())
      .contains("<turbo-frame id=\"backfill-999999-state\"")
      .contains("Update backfill field failed")
    assertThat(webTestClient.get(BackfillShowButtonHandlerAction.startPath("999999")).response.code)
      .isIn(404, 405)
  }

  private fun postStart(fetchSite: String?) =
    webTestClient.call(BackfillShowButtonHandlerAction.startPath("999999")) {
      header(FakeCallerAuthenticator.USER_HEADER, "diana")
      header(FakeCallerAuthenticator.CAPABILITIES_HEADER, "users")
      fetchSite?.let { header("Sec-Fetch-Site", it) }
      post(FormBody.Builder().add("field_id", "state").add("field_value", BackfillState.RUNNING.name).build())
    }
}
