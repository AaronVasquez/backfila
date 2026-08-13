package app.cash.backfila.dashboard

import app.cash.backfila.service.listener.BackfillRunListener
import app.cash.backfila.service.persistence.BackfilaDb
import app.cash.backfila.service.persistence.BackfillState
import app.cash.backfila.service.persistence.BackfillState.PAUSED
import app.cash.backfila.service.persistence.BackfillState.RUNNING
import app.cash.backfila.service.persistence.DbBackfillRun
import app.cash.backfila.service.persistence.DbEventLog
import jakarta.inject.Inject
import java.time.Clock
import misk.MiskCaller
import misk.exceptions.BadRequestException
import misk.hibernate.Id
import misk.hibernate.Query
import misk.hibernate.Transacter
import misk.hibernate.loadOrNull
import misk.logging.getLogger

class BackfillStateToggler @Inject constructor(
  @BackfilaDb private val transacter: Transacter,
  private val queryFactory: Query.Factory,
  private val backfillRunListeners: Set<BackfillRunListener>,
) {
  @Inject
  private lateinit var clock: Clock

  fun toggleRunningState(id: Long, caller: MiskCaller, desiredState: BackfillState) {
    toggleRunningState(id, caller, desiredState, approve = false)
  }

  internal fun approveAndStart(id: Long, caller: MiskCaller) {
    toggleRunningState(id, caller, RUNNING, approve = true)
  }

  private fun toggleRunningState(
    id: Long,
    caller: MiskCaller,
    desiredState: BackfillState,
    approve: Boolean,
  ) {
    val requiredCurrentState = when (desiredState) {
      PAUSED -> RUNNING
      RUNNING -> PAUSED
      else -> throw IllegalArgumentException("can only toggle to RUNNING or PAUSED")
    }

    transacter.transaction { session ->
      val run = session.loadOrNull<DbBackfillRun>(Id(id))
        ?: throw BadRequestException("backfill $id doesn't exist")
      logger.info {
        "Found backfill $id for `${run.registered_backfill.service.registry_name}`" +
          "::`${run.registered_backfill.name}`"
      }
      if (run.state != requiredCurrentState) {
        logger.info {
          "Backfill $id can't move to state $desiredState, " +
            "in state ${run.state}, requires $requiredCurrentState"
        }
        throw BadRequestException(
          "backfill $id isn't $requiredCurrentState, can't move to state $desiredState",
        )
      }
      if (desiredState == RUNNING) {
        enforceApproval(run, caller, approve)
      }
      run.setState(session, queryFactory, desiredState)

      val startedOrStopped = if (desiredState == RUNNING) "started" else "stopped"
      session.save(
        DbEventLog(
          run.id,
          partition_id = null,
          user = caller.principal,
          type = DbEventLog.Type.STATE_CHANGE,
          message = "backfill $startedOrStopped",
        ),
      )
    }

    if (desiredState == RUNNING) {
      backfillRunListeners.forEach { it.runStarted(Id(id), caller.principal) }
    } else {
      backfillRunListeners.forEach { it.runPaused(Id(id), caller.principal) }
    }
  }

  private fun enforceApproval(run: DbBackfillRun, caller: MiskCaller, approve: Boolean) {
    if (!run.registered_backfill.requires_approval) return

    val creator = run.created_by_user
      ?: throw BadRequestException("backfill ${run.id.id} is missing its creator")
    val approver = run.approved_by_user
    val approvedAt = run.approved_at
    if ((approver == null) != (approvedAt == null)) {
      throw BadRequestException("backfill ${run.id.id} has incomplete approval")
    }
    if (approver != null) {
      if (approver == creator) {
        throw BadRequestException("backfill ${run.id.id} was approved by its creator")
      }
      return
    }
    if (!approve) {
      throw BadRequestException("backfill ${run.id.id} requires approval before it can start")
    }

    val approvingUser = caller.user
      ?: throw BadRequestException("backfill ${run.id.id} must be approved by an authenticated user")
    if (approvingUser == creator) {
      throw BadRequestException("backfill ${run.id.id} can't be approved by its creator")
    }
    run.approved_by_user = approvingUser
    run.approved_at = clock.instant()
  }

  companion object {
    private val logger = getLogger<BackfillStateToggler>()
  }
}
