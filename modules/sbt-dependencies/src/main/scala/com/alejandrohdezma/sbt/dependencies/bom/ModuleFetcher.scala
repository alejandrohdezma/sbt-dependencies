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

package com.alejandrohdezma.sbt.dependencies.bom

import java.util.Date

import sbt._
import sbt.internal.librarymanagement.IvySbt

import com.alejandrohdezma.sbt.dependencies.finders.Utils
import com.alejandrohdezma.sbt.dependencies.model.Eq
import com.alejandrohdezma.sbt.dependencies.model.Eq._
import org.apache.ivy.core.cache.DefaultRepositoryCacheManager
import org.apache.ivy.core.module.descriptor.DefaultArtifact
import org.apache.ivy.core.module.descriptor.{Artifact => IvyArtifact}
import org.apache.ivy.core.module.id.ModuleRevisionId
import org.apache.ivy.core.report.DownloadStatus
import org.apache.ivy.core.resolve.DownloadOptions

/** Fetches a module's pom file. This is the seam between the pom model and sbt's dependency resolution, so tests can
  * substitute a stub serving pre-baked pom files.
  */
private[dependencies] trait ModuleFetcher {

  /** The pom file `moduleID` resolves to; fails the build when no configured resolver has it. */
  def fetch(moduleID: ModuleID): File

}

private[dependencies] object ModuleFetcher {

  /** A fetcher that downloads the module's pom as a plain Ivy artifact through the configured resolver chain.
    *
    * It deliberately bypasses `DependencyResolution.update`: resolving even an intransitive, pom-only module makes
    * Ivy's `PomModuleDescriptorParser` build module descriptors for the pom's whole parent/import closure, and for
    * every `packaging=pom` descriptor `PomModuleDescriptorBuilder.addMainArtifact` silently probes the network for a
    * main jar those modules never publish — a cold read of a large BOM tree (google-cloud's `libraries-bom` expands to
    * 200+ poms) used to take ~12 minutes with no output. A raw artifact download never parses the pom, costs at most
    * one existence check plus one GET per pom, and is served from the local Ivy artifact cache
    * (`~/.ivy2/cache/<org>/<module>/poms`) with zero network calls on warm loads.
    *
    * Since the raw download path serves whatever is cached, changing (`-SNAPSHOT`) revisions are evicted from the cache
    * before downloading so they refresh on every load — mirroring the refresh the resolve engine used to apply through
    * the cache's `.*-SNAPSHOT` changing pattern.
    */
  def fromIvy(ivySbt: IvySbt)(implicit log: Logger): ModuleFetcher = moduleID =>
    ivySbt.withIvy(log) { ivy =>
      val mrid = ModuleRevisionId.newInstance(moduleID.organization, moduleID.name, moduleID.revision)

      val pom = new DefaultArtifact(mrid, new Date(), moduleID.name, "pom", "pom")

      if (moduleID.revision.endsWith("-SNAPSHOT"))
        ivy.getSettings.getDefaultRepositoryCacheManager match {
          case manager: DefaultRepositoryCacheManager =>
            val cached = new File(manager.getRepositoryCacheRoot, manager.getArchivePathInCache(pom))
            if (cached.exists() && !cached.delete()) log.warn(s"Could not evict cached snapshot pom $cached")
          case _ => ()
        }

      val report = ivy.getSettings.getDefaultResolver.download(Array[IvyArtifact](pom), new DownloadOptions())

      report.getArtifactsReports.headOption.filter(_.getDownloadStatus !== DownloadStatus.FAILED) match {
        case Some(result) =>
          result.getLocalFile

        case None =>
          val details = report.getArtifactsReports.headOption.flatMap(report => Option(report.getDownloadDetails))

          Utils.fail {
            s"Failed to resolve $moduleID: ${details.getOrElse("pom not found in any of the configured resolvers")}"
          }
      }
    }

  /** Download-status equality; the statuses are singletons. */
  @SuppressWarnings(Array("scalafix:DisableSyntax.=="))
  implicit private val DownloadStatusEq: Eq[DownloadStatus] = (a, b) => a == b

}
