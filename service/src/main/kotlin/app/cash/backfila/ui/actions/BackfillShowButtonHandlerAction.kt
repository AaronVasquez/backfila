package app.cash.backfila.ui.actions

import app.cash.backfila.dashboard.CancelBackfillAction
import app.cash.backfila.dashboard.GetBackfillStatusAction
import app.cash.backfila.dashboard.SoftDeleteBackfillAction
import app.cash.backfila.dashboard.StartBackfillAction
import app.cash.backfila.dashboard.StartBackfillRequest
import app.cash.backfila.dashboard.StopBackfillAction
import app.cash.backfila.dashboard.StopBackfillRequest
import app.cash.backfila.dashboard.UpdateBackfillAction
import app.cash.backfila.dashboard.UpdateBackfillRequest
import app.cash.backfila.service.persistence.BackfillState
import app.cash.backfila.ui.components.AlertError
import app.cash.backfila.ui.components.DashboardPageLayout
import app.cash.backfila.ui.pages.BackfillShowAction.Companion.APPROVE_AND_START_STATE_BUTTON_LABEL
import app.cash.backfila.ui.pages.BackfillShowAction.Companion.APPROVE_AND_START_STATE_VALUE
import app.cash.backfila.ui.pages.BackfillShowAction.Companion.CANCEL_STATE_BUTTON_LABEL
import app.cash.backfila.ui.pages.BackfillShowAction.Companion.DELETE_STATE_BUTTON_LABEL
import app.cash.backfila.ui.pages.BackfillShowAction.Companion.PAUSE_STATE_BUTTON_LABEL
import app.cash.backfila.ui.pages.BackfillShowAction.Companion.START_STATE_BUTTON_LABEL
import jakarta.inject.Inject
import jakarta.inject.Singleton
import java.time.Instant
import kotlinx.html.ButtonType
import kotlinx.html.FormMethod
import kotlinx.html.InputType
import kotlinx.html.TagConsumer
import kotlinx.html.button
import kotlinx.html.div
import kotlinx.html.form
import kotlinx.html.input
import kotlinx.html.span
import misk.exceptions.BadRequestException
import misk.exceptions.UnauthorizedException
import misk.logging.getLogger
import misk.security.authz.Authenticated
import misk.tailwind.Link
import misk.turbo.turbo_frame
import misk.web.FormField
import misk.web.FormValue
import misk.web.PathParam
import misk.web.Post
import misk.web.RequestContentType
import misk.web.RequestHeader
import misk.web.Response
import misk.web.ResponseBody
import misk.web.ResponseContentType
import misk.web.actions.WebAction
import misk.web.mediatype.MediaTypes
import misk.web.toResponseBody
import okhttp3.Headers

@Singleton
class BackfillShowButtonHandlerAction @Inject constructor(
  private val dashboardPageLayout: DashboardPageLayout,
  private val startBackfillAction: StartBackfillAction,
  private val stopBackfillAction: StopBackfillAction,
  private val updateBackfillAction: UpdateBackfillAction,
  private val cancelBackfillAction: CancelBackfillAction,
  private val softDeleteBackfillAction: SoftDeleteBackfillAction,
  private val getBackfillStatusAction: GetBackfillStatusAction,
) : WebAction {
  @Post(PATH)
  @RequestContentType(MediaTypes.APPLICATION_FORM_URLENCODED)
  @ResponseContentType(MediaTypes.TEXT_HTML)
  @Authenticated(capabilities = ["users"])
  internal fun post(
    @PathParam id: String,
    @FormValue form: BackfillButtonForm,
    @RequestHeader("Sec-Fetch-Site") fetchSite: String?,
  ): Response<ResponseBody> {
    requireSameOriginBrowserRequest(fetchSite)
    return update(id, form.fieldId, form.fieldValue)
  }

  @Post(START_PATH)
  @RequestContentType(MediaTypes.APPLICATION_FORM_URLENCODED)
  @ResponseContentType(MediaTypes.TEXT_HTML)
  @Authenticated(capabilities = ["users"])
  internal fun start(
    @PathParam id: String,
    @FormValue form: BackfillButtonForm,
    @RequestHeader("Sec-Fetch-Site") fetchSite: String?,
  ): Response<ResponseBody> {
    requireSameOriginBrowserRequest(fetchSite)
    return try {
      startBackfillAction.start(id.toLong(), startBackfillRequest(form.fieldValue))
      handleStateFrameResponse(id)
    } catch (e: Exception) {
      handleError(e, id, "state")
    }
  }

  fun get(id: String, field_id: String?, field_value: String?): Response<ResponseBody> =
    update(id, field_id, field_value)

  private fun update(id: String, fieldId: String?, fieldValue: String?): Response<ResponseBody> {
    try {
      if (!fieldId.isNullOrBlank()) {
        handleFieldUpdate(id.toLong(), fieldId, fieldValue)
      }
    } catch (e: Exception) {
      return handleError(e, id, fieldId)
    }

    return when (fieldId) {
      "state" -> handleStateFrameResponse(id)
      else -> handleRedirectResponse(id)
    }
  }

  private fun handleFieldUpdate(id: Long, fieldId: String, fieldValue: String?) {
    when (fieldId) {
      "state" -> handleStateUpdate(id, fieldValue)
      else -> handleConfigUpdate(id, fieldId, fieldValue)
    }
  }

  private fun handleStateUpdate(id: Long, value: String?) {
    when (value) {
      BackfillState.PAUSED.name -> stopBackfillAction.stop(id, StopBackfillRequest())
      BackfillState.RUNNING.name, APPROVE_AND_START_STATE_VALUE ->
        throw BadRequestException("Start backfills with ${startPath(id.toString())}")
      BackfillState.CANCELLED.name -> cancelBackfillAction.cancel(id)
      "soft_delete" -> softDeleteBackfillAction.softDelete(id)
    }
  }

  private fun handleConfigUpdate(id: Long, fieldId: String, value: String?) {
    val request = when (fieldId) {
      "num_threads" -> value?.toIntOrNull()?.let { UpdateBackfillRequest(num_threads = it) }
      "scan_size" -> value?.toLongOrNull()?.let { UpdateBackfillRequest(scan_size = it) }
      "batch_size" -> value?.toLongOrNull()?.let { UpdateBackfillRequest(batch_size = it) }
      "extra_sleep_ms" -> value?.toLongOrNull()?.let { UpdateBackfillRequest(extra_sleep_ms = it) }
      "backoff_schedule" -> value?.let { UpdateBackfillRequest(backoff_schedule = it) }
      else -> null
    }
    request?.let { updateBackfillAction.update(id, it) }
  }

  private fun handleError(e: Exception, id: String, fieldId: String?): Response<ResponseBody> {
    logger.error(e) { "Update backfill field failed $e" }
    val errorHtmlResponseBody = dashboardPageLayout.newBuilder()
      .buildHtmlResponseBody {
        if (fieldId == "state") {
          turbo_frame("backfill-$id-state") { renderUpdateError(e) }
        } else {
          renderUpdateError(e)
        }
      }
    return Response(
      body = errorHtmlResponseBody,
      statusCode = 422,
      headers = Headers.headersOf("Content-Type", MediaTypes.TEXT_HTML),
    )
  }

  private fun handleStateFrameResponse(id: String): Response<ResponseBody> {
    val backfillStatus = getBackfillStatusAction.status(id.toLong())
    val currentState = backfillStatus.state
    val deletedAt = backfillStatus.deleted_at

    val frameContent = dashboardPageLayout.newBuilder()
      .buildHtmlResponseBody {
        turbo_frame("backfill-$id-state") {
          div("flex items-start gap-2") {
            div("flex flex-col") {
              renderStateButtonsWithApproval(
                id,
                currentState,
                deletedAt,
                backfillStatus.requires_approval && backfillStatus.approved_by_user == null,
                canApproveBackfill(backfillStatus.created_by_user, dashboardPageLayout.currentUser),
              )
            }
            div("flex items-center gap-2") {
              span("text-sm font-bold text-gray-500") { +"State:" }
              span("text-sm font-semibold text-gray-900") { +currentState.name }
            }
          }
        }
      }

    return Response(
      body = frameContent,
      statusCode = 200,
      headers = Headers.headersOf("Content-Type", MediaTypes.TEXT_HTML),
    )
  }

  fun TagConsumer<*>.renderStateButtons(
    id: String,
    currentState: BackfillState,
    deletedAt: Instant? = null,
  ) = renderStateButtonsWithApproval(id, currentState, deletedAt, approvalRequired = false)

  internal fun TagConsumer<*>.renderStateButtonsWithApproval(
    id: String,
    currentState: BackfillState,
    deletedAt: Instant? = null,
    approvalRequired: Boolean,
    canApprove: Boolean = true,
  ) {
    if (currentState == BackfillState.PAUSED && approvalRequired && !canApprove) {
      button(classes = "rounded-full bg-gray-400 px-3 py-1.5 text-sm font-semibold text-white") {
        disabled = true
        +"Another user must approve"
      }
    } else {
      getStateButton(currentState, approvalRequired)?.let { button ->
        val isStart = button.href == BackfillState.RUNNING.name || button.href == APPROVE_AND_START_STATE_VALUE
        renderButtonWithApproval(id, "state", button, if (isStart) "green" else "yellow")
      }
    }
    getCancelButton(currentState)?.let { button ->
      renderButton(id, "state", button, "red")
    }
    getDeleteButton(currentState, deletedAt)?.let { button ->
      renderButton(id, "state", button, "gray")
    }
  }

  fun TagConsumer<*>.renderButton(id: String, fieldId: String, button: Link, color: String) {
    renderButtonWithApproval(id, fieldId, button, color)
  }

  private fun TagConsumer<*>.renderButtonWithApproval(
    id: String,
    fieldId: String,
    button: Link,
    color: String,
  ) {
    val approve = button.href == APPROVE_AND_START_STATE_VALUE
    form(classes = "m-0 pb-1") {
      action = if (button.href == BackfillState.RUNNING.name || approve) startPath(id) else path(id)
      method = FormMethod.post
      input {
        type = InputType.hidden
        name = "field_id"
        value = fieldId
      }
      input {
        type = InputType.hidden
        name = "field_value"
        value = button.href
      }
      button(
        classes = "rounded-full bg-$color-600 px-3 py-1.5 text-sm font-semibold text-white shadow-sm hover:bg-$color-500 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-$color-600",
      ) {
        type = ButtonType.submit
        if (approve) attributes["onclick"] = "return confirm('Approve and start this backfill?')"
        +button.label
      }
    }
  }

  private fun handleRedirectResponse(id: String): Response<ResponseBody> {
    return Response(
      body = "go to /backfills/$id".toResponseBody(),
      statusCode = 303,
      headers = Headers.headersOf("Location", "/backfills/$id"),
    )
  }

  private fun getCancelButton(state: BackfillState): Link? {
    return when (state) {
      BackfillState.PAUSED -> Link(
        label = CANCEL_STATE_BUTTON_LABEL,
        href = BackfillState.CANCELLED.name,
      )
      else -> null
    }
  }

  private fun getDeleteButton(state: BackfillState, deletedAt: Instant?): Link? {
    if (deletedAt != null) {
      return null
    }
    return when (state) {
      BackfillState.COMPLETE, BackfillState.CANCELLED -> Link(
        label = DELETE_STATE_BUTTON_LABEL,
        href = "soft_delete",
      )
      else -> null
    }
  }

  companion object {
    private val logger = getLogger<BackfillShowButtonHandlerAction>()

    const val PATH = "/api/backfill/{id}/update"
    internal const val START_PATH = "/api/backfill/{id}/start"
    fun path(id: String) = PATH.replace("{id}", id)
    fun path(id: Long) = path(id.toString())
    internal fun startPath(id: String) = START_PATH.replace("{id}", id)
  }
}

internal data class BackfillButtonForm(
  @FormField("field_id") val fieldId: String?,
  @FormField("field_value") val fieldValue: String?,
)

private fun TagConsumer<*>.renderUpdateError(e: Exception) = div("py-20") {
  AlertError(message = "Update backfill field failed: $e", label = "Try Again", onClick = "history.back(); return false;")
}

internal fun requireSameOriginBrowserRequest(fetchSite: String?) {
  if (fetchSite != "same-origin") {
    throw UnauthorizedException("Cross-site backfill updates are not permitted")
  }
}

internal fun startBackfillRequest(stateValue: String?) = when (stateValue) {
  BackfillState.RUNNING.name -> StartBackfillRequest()
  APPROVE_AND_START_STATE_VALUE -> StartBackfillRequest(approve = true)
  else -> throw BadRequestException("Unknown backfill start action")
}

internal fun canApproveBackfill(createdByUser: String?, currentUser: String?) =
  createdByUser != null && currentUser != null && createdByUser != currentUser

internal fun getStateButton(state: BackfillState, approvalRequired: Boolean): Link? = when (state) {
  BackfillState.PAUSED -> Link(
    label = if (approvalRequired) APPROVE_AND_START_STATE_BUTTON_LABEL else START_STATE_BUTTON_LABEL,
    href = if (approvalRequired) APPROVE_AND_START_STATE_VALUE else BackfillState.RUNNING.name,
  )
  // COMPLETE and CANCELLED represent final states.
  BackfillState.COMPLETE, BackfillState.CANCELLED -> null
  else -> Link(
    label = PAUSE_STATE_BUTTON_LABEL,
    href = BackfillState.PAUSED.name,
  )
}
