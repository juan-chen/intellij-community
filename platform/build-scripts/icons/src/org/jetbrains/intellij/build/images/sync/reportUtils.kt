// Copyright 2000-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package org.jetbrains.intellij.build.images.sync

import java.io.File
import java.util.*
import java.util.function.Consumer
import java.util.stream.Stream
import kotlin.streams.toList

internal fun report(context: Context,
                    root: File,
                    devIcons: Int,
                    icons: Int,
                    skipped: Int,
                    consistent: Collection<String>,
                    errorHandler: Consumer<String>) {
  log("Skipped $skipped dirs")
  fun Collection<String>.logIcons(description: String) = "$size $description${if (size < 100) ": ${joinToString()}" else ""}"
  val report = """
    |$devIcons icons are found in dev repo:
    | ${context.addedByDev.logIcons("added")}
    | ${context.removedByDev.logIcons("removed")}
    | ${context.modifiedByDev.logIcons("modified")}
    |$icons icons are found in icons repo:
    | ${context.addedByDesigners.logIcons("added")}
    | ${context.removedByDesigners.logIcons("removed")}
    | ${context.modifiedByDesigners.logIcons("modified")}
    |${consistent.size} consistent icons in both repos
  """.trimMargin()
  log(report)
  if (isUnderTeamCity()) {
    createReviews(root, context)
    val investigator = if (!context.isSuccess() && context.assignInvestigation && !isInvestigationAssigned()) {
      assignInvestigation(root, context)
    }
    else null
    if (context.notifySlack) sendNotification(investigator, context)
    if (!context.isSuccess()) errorHandler.accept(report)
  }
}

private val UPSOURCE_ICONS_PROJECT_ID = System.getProperty("intellij.icons.upsource.project.id")
private val UPSOURCE_DEV_PROJECT_ID = System.getProperty("intellij.icons.upsource.dev.project.id")

private fun Map<File, Collection<CommitInfo>>.commitMessage() = "Synchronization of changed icons from\n" + map {
  "${getOriginUrl(it.key)}: ${it.value.map(CommitInfo::hash)}"
}.joinToString(System.lineSeparator())

private fun createReviewForDev(root: File, context: Context, user: String, email: String): Review? {
  if (!context.doSyncDevIconsAndCreateReview) return null
  val changes = context.addedByDesigners + context.removedByDesigners + context.modifiedByDesigners
  if (changes.isNotEmpty()) {
    val commits = findInvestigator(context.iconsRepo, changes.asSequence()).commits
    if (commits.isNotEmpty()) {
      val review = pushAndCreateReview(UPSOURCE_DEV_PROJECT_ID, user, email, commits.commitMessage(), changes.asSequence()
        .map { File(root, it).absolutePath }
        .map { findGitRepoRoot(it, silent = true) }
        .distinct()
        .toList())
      addReviewer(UPSOURCE_DEV_PROJECT_ID, review, triggeredBy() ?: DEFAULT_INVESTIGATOR)
      try {
        context.devIconsVerifier?.run()
      }
      catch (e: Exception) {
        e.printStackTrace()
        postComment(UPSOURCE_DEV_PROJECT_ID, review, "Some checks were failed, see build log ${thisBuildReportableLink()}")
      }
      return review
    }
  }
  return null
}

private fun createReviewForIcons(root: File, context: Context, user: String, email: String): Review? {
  if (!context.doSyncIconsAndCreateReview) return null
  val changes = context.addedByDev + context.removedByDev + context.modifiedByDev
  if (changes.isNotEmpty()) {
    val investigator = findInvestigator(root, changes.asSequence())
    if (investigator.commits.isNotEmpty()) {
      val review = pushAndCreateReview(UPSOURCE_ICONS_PROJECT_ID, user, email,
                                       investigator.commits.commitMessage(),
                                       listOf(context.iconsRepo))
      addReviewer(UPSOURCE_ICONS_PROJECT_ID, review, investigator.email)
      return review
    }
  }
  return null
}

private fun createReviews(root: File, context: Context) = callSafely {
  val (user, email) = System.getProperty("upsource.user.name") to System.getProperty("upsource.user.email")
  context.createdReviews = Stream.of(
    { createReviewForDev(root, context, user, email) },
    { createReviewForIcons(root, context, user, email) }
  ).parallel().map { it() }
    .filter(Objects::nonNull)
    .map { it as Review }
    .toList()
}

private fun assignInvestigation(root: File, context: Context): Investigator? =
  callSafely {
    assignInvestigation(findInvestigator(root, context.addedByDev.asSequence() +
                                               context.removedByDev.asSequence() +
                                               context.modifiedByDev.asSequence()), context)
  }

private fun findInvestigator(root: File, changes: Sequence<String>): Investigator {
  val commits = changes.map {
    val path = File(root, it).absolutePath
    val commit = latestChangeCommit(path)
    if (commit != null) commit to it else null
  }.filterNotNull().toList()
  return commits
           .groupBy { it.first.committerEmail }
           .maxBy { it.value.size }
           ?.let { entry ->
             Investigator(entry.key, commits.asSequence()
               .map { it.first }
               .distinctBy { it.hash }
               .groupBy { it.repo })
           } ?: Investigator()
}

private fun pushAndCreateReview(project: String, user: String, email: String, message: String, repos: Collection<File>): Review {
  val branch = "icons-sync/${UUID.randomUUID()}"
  try {
    val commits = repos.map {
      initGit(it, user, email)
      commitAndPush(it, branch, message)
    }
    return createReview(project, branch, commits).also {
      log("Review successfully created: ${it.url}")
    }
  }
  catch (e: Throwable) {
    repos.forEach {
      deleteBranch(it, branch)
    }
    throw e
  }
}

private fun sendNotification(investigator: Investigator?, context: Context) {
  callSafely {
    if (isNotificationRequired(context)) {
      notifySlackChannel(investigator, context)
    }
  }
}

private val CHANNEL_WEB_HOOK = System.getProperty("intellij.icons.slack.channel")
private val INTELLIJ_ICONS_SYNC_RUN_CONF = System.getProperty("intellij.icons.sync.run.conf")

private fun notifySlackChannel(investigator: Investigator?, context: Context) {
  val investigation = when {
    investigator == null -> ""
    investigator.isAssigned -> "Investigation is assigned to ${investigator.email}\n"
    else -> "Unable to assign investigation to ${investigator.email}\n"
  }
  val hint = when {
    context.isSuccess() -> ""
    context.createdReviews.isNotEmpty() -> "Reviews created: ${context.createdReviews.joinToString { "<${it.url}|${it.id}>" }}\n"
    else -> "Use 'Icons processing/*$INTELLIJ_ICONS_SYNC_RUN_CONF*' IDEA Ultimate run configuration\n"
  }
  val reaction = if (context.isSuccess()) ":white_check_mark:" else ":scream:"
  val build = "<${thisBuildReportableLink()}|See build log>"
  val text = "*${System.getProperty("teamcity.buildConfName")}* $reaction\n$investigation$hint$build"
  val response = post(CHANNEL_WEB_HOOK, """{ "text": "$text" }""")
  if (response != "ok") error("$CHANNEL_WEB_HOOK responded with $response")
}
