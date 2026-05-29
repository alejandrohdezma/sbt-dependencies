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

import java.net.InetSocketAddress
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

import scala.concurrent.duration._
import scala.language.reflectiveCalls
import scala.util.Using
import scala.util.chaining._

import sbt.util.Logger

import com.alejandrohdezma.sbt.dependencies.TestLogger
import com.alejandrohdezma.sbt.dependencies.model.Eq._
import coursier.MavenRepository
import coursier.core.Authentication
import munit.AnyFixture

import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer

class AgeCheckerSuite extends munit.FunSuite {

  implicit val logger: Logger = TestLogger()

  // --- HTTP integration tests: age comparisons against a fixed clock ---

  // 2026-02-01 12:00:00 UTC — the publish date the `/200/` handler reports.
  private val publishedAt = Instant.parse("2026-02-01T12:00:00Z")

  test("check returns Right(()) when the artifact is older than the cooldown") {
    val clock   = () => publishedAt.plusSeconds(10 * 24 * 60 * 60) // 10 days after publish
    val checker = AgeChecker(Seq(MavenRepository(serverFixture.serverUrl("/200/"))), now = clock, timeoutSeconds = 5)

    assertEquals(checker.check("org.example", "lib", "1.0.0", 7.days), Right(()))
  }

  test("check returns Left(TooRecent) when the artifact is younger than the cooldown") {
    val sixDays = 6 * 24 * 60 * 60L
    val clock   = () => publishedAt.plusSeconds(sixDays) // 6 days after publish, cooldown is 7
    val checker = AgeChecker(Seq(MavenRepository(serverFixture.serverUrl("/200/"))), now = clock, timeoutSeconds = 5)

    val result = checker.check("org.example", "lib", "1.0.0", 7.days)

    assertEquals(result, Left(AgeChecker.TooRecent(observedAge = sixDays.seconds, requiredAge = 7.days)))
  }

  test("check degrades to Right(()) when no Last-Modified header is returned") {
    val clock   = () => publishedAt
    val checker =
      AgeChecker(Seq(MavenRepository(serverFixture.serverUrl("/no-date/"))), now = clock, timeoutSeconds = 5)

    assertEquals(checker.check("org.example", "lib", "1.0.0", 7.days), Right(()))
  }

  test("check degrades to Right(()) when the POM is missing (404)") {
    val clock   = () => publishedAt
    val checker = AgeChecker(Seq(MavenRepository(serverFixture.serverUrl("/404/"))), now = clock, timeoutSeconds = 5)

    assertEquals(checker.check("org.example", "lib", "1.0.0", 7.days), Right(()))
  }

  // --- HTTP integration tests: pomLastModified directly ---

  test("pomLastModified parses Last-Modified header from HEAD response") {
    val repo = MavenRepository(serverFixture.serverUrl("/200/"))

    val result = AgeChecker.pomLastModified(
      Seq(repo), credentials = Nil, organization = "org.example", name = "lib", version = "1.0.0", timeoutSeconds = 5
    )

    assertEquals(result, Some(publishedAt))
  }

  test("pomLastModified returns None on 404") {
    val repo = MavenRepository(serverFixture.serverUrl("/404/"))

    val result = AgeChecker.pomLastModified(
      Seq(repo), credentials = Nil, organization = "org.example", name = "lib", version = "1.0.0", timeoutSeconds = 5
    )

    assertEquals(result, None)
  }

  test("pomLastModified returns None when response lacks Last-Modified") {
    val repo = MavenRepository(serverFixture.serverUrl("/no-date/"))

    val result = AgeChecker.pomLastModified(
      Seq(repo), credentials = Nil, organization = "org.example", name = "lib", version = "1.0.0", timeoutSeconds = 5
    )

    assertEquals(result, None)
  }

  test("pomLastModified sends credentials from per-repo Authentication") {
    val repo = MavenRepository(
      serverFixture.serverUrl("/auth/"),
      authentication = Some(
        Authentication(
          user = "user", password = "pass", optional = true, // send Authorization header up front, don't wait for a 401 challenge
          realmOpt = None, httpsOnly = false,                // 127.0.0.1 is HTTP
          passOnRedirect = false
        )
      )
    )

    val result = AgeChecker.pomLastModified(
      Seq(repo), credentials = Nil, organization = "org.example", name = "lib", version = "1.0.0", timeoutSeconds = 5
    )

    assertEquals(result, Some(Instant.parse("2026-03-03T12:00:00Z")))
  }

  test("pomLastModified returns None when an auth-required repo has no credentials") {
    val repo = MavenRepository(serverFixture.serverUrl("/auth/"))

    val result = AgeChecker.pomLastModified(
      Seq(repo), credentials = Nil, organization = "org.example", name = "lib", version = "1.0.0", timeoutSeconds = 5
    )

    assertEquals(result, None)
  }

  test("pomLastModified tries repositories in order; first non-empty Last-Modified wins") {
    val notFound = MavenRepository(serverFixture.serverUrl("/404/"))
    val ok       = MavenRepository(serverFixture.serverUrl("/200/"))

    val result = AgeChecker.pomLastModified(
      Seq(notFound, ok),
      credentials = Nil,
      organization = "org.example",
      name = "lib",
      version = "1.0.0",
      timeoutSeconds = 5
    )

    assertEquals(result, Some(publishedAt))
  }

  // --- Maven Central smoke test (network-dependent) ---

  test("pomLastModified against Maven Central returns a Last-Modified for scala-library 2.13.0") {
    val central = MavenRepository("https://repo1.maven.org/maven2/")

    val result = AgeChecker.pomLastModified(
      Seq(central), credentials = Nil, organization = "org.scala-lang", name = "scala-library", version = "2.13.0",
      timeoutSeconds = 30
    )

    // 2.13.0 was published in 2019, so the Last-Modified must be well before "now". Don't pin the
    // exact instant — Maven Central could in principle re-stamp — but require it sits in a sensible
    // window.
    val earliestPlausible = Instant.parse("2019-01-01T00:00:00Z")
    val now               = Instant.now()
    assert(result.isDefined, "expected Maven Central to return a Last-Modified for scala-library 2.13.0")
    assert(result.exists(_.isAfter(earliestPlausible)), s"expected $result to be after $earliestPlausible")
    assert(result.exists(_.isBefore(now)), s"expected $result to be before $now")
  }

  test("AgeChecker against Maven Central treats scala-library 2.13.0 as past a 1-day cooldown") {
    val central = MavenRepository("https://repo1.maven.org/maven2/")
    val checker = AgeChecker(Seq(central), timeoutSeconds = 30)

    assertEquals(checker.check("org.scala-lang", "scala-library", "2.13.0", 1.day), Right(()))
  }

  // --- cached decorator ---

  test("cached calls underlying once per (org, name, version, minimumAge)") {
    val checkCalls = new AtomicInteger(0)

    val underlying: AgeChecker = (_, _, _, _) => {
      checkCalls.incrementAndGet()
      Right(())
    }

    val cached = underlying.cached

    cached.check("org.example", "lib", "1.0.0", 7.days)
    cached.check("org.example", "lib", "1.0.0", 7.days)
    cached.check("org.example", "lib", "1.0.0", 7.days)

    assertEquals(checkCalls.get(), 1)
  }

  test("cached treats different tuples as separate cache keys") {
    val checkCalls = new AtomicInteger(0)

    val underlying: AgeChecker = (_, _, _, _) => {
      checkCalls.incrementAndGet()
      Right(())
    }

    val cached = underlying.cached

    cached.check("org.example", "lib", "1.0.0", 7.days)
    cached.check("org.example", "lib", "2.0.0", 7.days)      // different version
    cached.check("org.other", "lib", "1.0.0", 7.days)        // different org
    cached.check("org.example", "lib_2.13", "1.0.0", 7.days) // different name
    cached.check("org.example", "lib", "1.0.0", 14.days)     // different minimumAge

    assertEquals(checkCalls.get(), 5)
  }

  test("cached preserves the original return value (including Left)") {
    val underlying: AgeChecker =
      (_, _, _, _) => Left(AgeChecker.TooRecent(observedAge = 1.day, requiredAge = 7.days))

    val cached = underlying.cached

    val r1 = cached.check("org.example", "lib", "1.0.0", 7.days)
    val r2 = cached.check("org.example", "lib", "1.0.0", 7.days)

    assertEquals(r1, Left(AgeChecker.TooRecent(observedAge = 1.day, requiredAge = 7.days)))
    assertEquals(r1, r2)
  }

  //////////////
  // Fixtures //
  //////////////

  /** In-process HTTP server with deterministic responses for the HEAD tests. Started once per suite. */
  val serverFixture = new AnyFixture[HttpServer]("Http Server") {

    private def headHandler(status: Int, lastModified: Option[String]): HttpHandler =
      Using.resource(_) {
        case exchange if exchange.getRequestMethod !== "HEAD" =>
          exchange.sendResponseHeaders(405, -1)
        case exchange =>
          lastModified.foreach(exchange.getResponseHeaders.set("Last-Modified", _))
          exchange.sendResponseHeaders(status, -1) // -1 → no body, headers only
      }

    private val authHandler: HttpHandler =
      Using.resource(_) {
        case exchange if exchange.getRequestMethod !== "HEAD" =>
          exchange.sendResponseHeaders(405, -1)
        case exchange if Option(exchange.getRequestHeaders.getFirst("Authorization")).isEmpty =>
          exchange.sendResponseHeaders(401, -1)
        case exchange =>
          exchange.getResponseHeaders.set("Last-Modified", "Tue, 03 Mar 2026 12:00:00 GMT")
          exchange.sendResponseHeaders(200, -1)
      }

    // The server has to be constructed once and shared across `apply` / `beforeAll` / `afterAll` /
    // every test that calls `serverUrl` — otherwise each call creates and binds a fresh
    // (unstarted) server, every test hits an unbound port, and the suite hangs on read timeouts.
    private lazy val server: HttpServer =
      HttpServer
        .create(new InetSocketAddress("127.0.0.1", 0), 0)
        .tap(_.createContext("/200/", headHandler(200, lastModified = Some("Sun, 01 Feb 2026 12:00:00 GMT"))))
        .tap(_.createContext("/404/", headHandler(404, lastModified = None)))
        .tap(_.createContext("/no-date/", headHandler(200, lastModified = None)))
        .tap(_.createContext("/auth/", authHandler))
        .tap(_.setExecutor(Executors.newCachedThreadPool(new Thread(_).tap(_.setDaemon(true)))))

    override def apply(): HttpServer = server

    override def beforeAll(): Any = server.start()

    def serverUrl(path: String): String = s"http://127.0.0.1:${server.getAddress.getPort}$path"

    override def afterAll(): Any = server.stop(0)

  }

  override def munitFixtures: Seq[AnyFixture[_]] = super.munitFixtures :+ serverFixture

}
