/*
 * Copyright 2025-2026 Alejandro Hernández <https://github.com/alejandrohdezma>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.alejandrohdezma.sbt.dependencies.finders

import java.net.HttpURLConnection
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.duration._
import scala.util.Try

import sbt.util.Logger

import lmcoursier.internal.shaded.coursier.MavenRepository
import lmcoursier.internal.shaded.coursier.cache.ConnectionBuilder
import lmcoursier.internal.shaded.coursier.cache.FileCache
import lmcoursier.internal.shaded.coursier.credentials.DirectCredentials

/** Abstraction for deciding whether a published artifact is old enough to satisfy a configured cooldown.
  *
  * Implementations typically read the publish date from the Maven `Last-Modified` HTTP header. Errors (network failure,
  * missing header, non-2xx response) degrade to `Right(())` — we never block an update because we couldn't determine
  * the age.
  *
  * `name` is the resolved Maven artifact name (e.g. `cats-core_2.13`, `sbt-scalafix_2.12_1.0`, `guava`). Callers
  * resolve the cross-version suffix via `VersionFinder.mavenArtifactName` before invoking; cooldown-rule matching
  * (against the bare artifact name) lives in `CooldownFinder`, separately.
  */
trait AgeChecker {

  /** @return
    *   `Right(())` when the artifact is at least `minimumAge` old, or when its age couldn't be determined.
    *   `Left(TooRecent(...))` when it's younger than `minimumAge`.
    */
  def check(
      organization: String,
      name: String,
      version: String,
      minimumAge: FiniteDuration
  ): Either[AgeChecker.TooRecent, Unit]

}

object AgeChecker {

  /** Records what we observed and what was required, for callers to surface in debug logs. */
  final case class TooRecent(observedAge: FiniteDuration, requiredAge: FiniteDuration)

  /** Constructs an `AgeChecker` backed by an HTTP HEAD against each configured repository's POM URL.
    *
    * Auth, proxy, SSL config, and HTTP↔HTTPS redirects are delegated to `coursier.cache.ConnectionBuilder` — the same
    * machinery Coursier uses for downloading artifacts. Per-repo auth comes from each `MavenRepository.authentication`;
    * system-wide credentials are read from `~/.config/coursier/credentials.properties` (or the standard env vars) via a
    * default `coursier.cache.FileCache`.
    *
    * Falls back gracefully on any failure (see [[pomLastModified]]).
    *
    * @param repositories
    *   Maven repositories to HEAD in order; the first repo that returns a `Last-Modified` header wins.
    * @param now
    *   by-name so tests can inject a fixed clock.
    * @param timeoutSeconds
    *   per-HEAD timeout; total time spent across repositories is `repositories.size * timeoutSeconds` worst case.
    */
  def apply(
      repositories: Seq[MavenRepository],
      now: () => Instant = () => Instant.now(),
      timeoutSeconds: Int = 10
  )(implicit logger: Logger): AgeChecker = {
    val credentials = FileCache().credentials.flatMap(_.get())

    (org, name, version, minimumAge) =>
      pomLastModified(repositories, credentials, org, name, version, timeoutSeconds) match {
        case None =>
          logger.debug(s"Could not determine publish date for $org:$name:$version — cooldown skipped")
          Right(())

        case Some(publishedAt) =>
          val age = Duration.between(publishedAt, now()).toMillis.millis
          if (age >= minimumAge) Right(())
          else Left(TooRecent(observedAge = age, requiredAge = minimumAge))
      }
  }

  /** HEAD {repo}/{org-slashed}/{name}/{version}/{name}-{version}.pom against each configured repository, returning the
    * first `Last-Modified` header. Each request is tried in isolation; one failure doesn't poison the others.
    */
  def pomLastModified(
      repositories: Seq[MavenRepository],
      credentials: Seq[DirectCredentials],
      organization: String,
      name: String,
      version: String,
      timeoutSeconds: Int
  )(implicit logger: Logger): Option[Instant] = {
    val pomPath = s"${organization.replace('.', '/')}/$name/$version/$name-$version.pom"

    repositories.iterator.map { repo =>
      val base = if (repo.root.endsWith("/")) repo.root else repo.root + "/"
      val url  = base + pomPath

      val connectionBuilder = ConnectionBuilder(url)
        .withAuthentication(repo.authentication)
        .withAutoCredentials(credentials)
        .withMethod("HEAD")
        .withFollowHttpToHttpsRedirections(true)

      Try[Option[Instant]] {
        connectionBuilder.connection() match {
          case http: HttpURLConnection =>
            http.setConnectTimeout(timeoutSeconds * 1000)
            http.setReadTimeout(timeoutSeconds * 1000)

            val responseCode    = http.getResponseCode
            val lastModifiedRaw = http.getLastModified
            http.disconnect()

            if (responseCode >= 200 && responseCode < 300 && lastModifiedRaw > 0L)
              Some(Instant.ofEpochMilli(lastModifiedRaw))
            else None

          case other =>
            logger.debug(s"Skipping non-HTTP connection for $url: ${other.getClass.getName}")
            None
        }
      }.recover { case e =>
        logger.debug(s"HEAD $url failed: ${e.getMessage}")
        None
      }.toOption.flatMap(identity)
    }.collectFirst { case Some(instant) => instant }
  }

  implicit class AgeCheckerOps(private val underlying: AgeChecker) extends AnyVal {

    /** Memoizes the `check` result per `(org, name, version, minimumAge)`. Thread-safe because
      * `Utils.resolveLatestVersions` parallelizes via Futures (`finders/Utils.scala:46-52`).
      */
    def cached: AgeChecker = {
      val cache = new ConcurrentHashMap[(String, String, String, FiniteDuration), Either[AgeChecker.TooRecent, Unit]]()

      (organization, name, version, minimumAge) =>
        cache.computeIfAbsent(
          (organization, name, version, minimumAge),
          { case (o, n, v, m) => underlying.check(o, n, v, m) }
        )
    }

  }

}
