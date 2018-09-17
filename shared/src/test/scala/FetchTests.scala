/*
 * Copyright 2016-2018 47 Degrees, LLC. <http://www.47deg.com>
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

package fetch

import scala.concurrent.{ExecutionContext, Future}
import org.scalatest.{AsyncFreeSpec, Matchers}

import cats.data.NonEmptyList
import cats.instances.list._
import cats.syntax.apply._

import fetch._
import fetch.implicits._

class FetchTests extends AsyncFreeSpec with Matchers {
  import TestHelper._

  val ME = FetchMonadError[Future]

  implicit override def executionContext = ExecutionContext.Implicits.global

  "We can lift plain values to Fetch" in {
    val fetch: Fetch[Int] = Fetch.pure(42)
    Fetch.run[Future](fetch).map(_ shouldEqual 42)
  }

  "Data sources with errors throw fetch failures" in {
    val fetch: Fetch[Int] = Fetch(Never())
    val fut               = Fetch.runEnv[Future](fetch)

    ME.attempt(fut)
      .map(either =>
        either should matchPattern {
          case Left(NotFound(env, FetchOne(Never(), _))) =>
      })
  }

  "Data sources with errors throw fetch failures that can be handled" in {
    val fetch: Fetch[Int] = Fetch(Never())
    val fut               = Fetch.run[Future](fetch)
    ME.handleErrorWith(fut)(err => Future.successful(42)).map(_ shouldEqual 42)
  }

  "Data sources with errors and cached values throw fetch failures with the cache" in {
    val fetch: Fetch[Int] = Fetch(Never())
    val cache = InMemoryCache(
      OneSource.identity(One(1)) -> 1
    )

    ME.attempt(Fetch.run[Future](fetch, cache)).map {
      case Left(NotFound(env, _)) => env.cache shouldEqual cache
      case _                      => fail("Cache should be populated")
    }
  }

  "Data sources with errors won't fail if they're cached" in {
    val fetch: Fetch[Int] = Fetch(Never())
    val cache = InMemoryCache(
      NeverSource.identity(Never()) -> 1
    )
    Fetch.run[Future](fetch, cache).map(_ shouldEqual 1)
  }

  "We can lift errors to Fetch" in {
    val fetch: Fetch[Int] = Fetch.error(DidNotFound())

    ME.attempt(Fetch.run[Future](fetch)).map {
      case Left(UnhandledException(_, DidNotFound())) => assert(true)
      case _                                          => fail("Should've thrown NotFound exception")
    }
  }

  "We can lift handle and recover from errors in Fetch" in {
    import cats.syntax.applicativeError._

    val fetch: Fetch[Int] = Fetch.error(DidNotFound())
    val fut               = Fetch.run[Future](fetch)
    ME.handleErrorWith(fut)(err => Future.successful(42)).map(_ shouldEqual 42)
  }

  "We can lift values which have a Data Source to Fetch" in {
    Fetch.run[Future](one(1)).map(_ shouldEqual 1)
  }

  "We can map over Fetch values" in {
    val fetch = one(1).map(_ + 1)
    Fetch.run[Future](fetch).map(_ shouldEqual 2)
  }

  "We can use fetch inside a for comprehension" in {
    val fetch = for {
      o <- one(1)
      t <- one(2)
    } yield (o, t)

    Fetch.run[Future](fetch).map(_ shouldEqual (1, 2))
  }

  "Monadic bind implies sequential execution" in {
    val fetch = for {
      o <- one(1)
      t <- one(2)
    } yield (o, t)

    Fetch.runEnv[Future](fetch).map(_.rounds.size shouldEqual 2)
  }

  "We can mix data sources" in {
    val fetch: Fetch[(Int, List[Int])] = for {
      o <- one(1)
      m <- many(3)
    } yield (o, m)

    Fetch.run[Future](fetch).map(_ shouldEqual (1, List(0, 1, 2)))
  }

  "We can use Fetch as a cartesian" in {
    import cats.syntax.cartesian._

    val fetch: Fetch[(Int, List[Int])] = (one(1), many(3)).tupled
    val fut                            = Fetch.run[Future](fetch)

    fut.map(_ shouldEqual (1, List(0, 1, 2)))
  }

  "We can use Fetch as an applicative" in {
    import cats.syntax.cartesian._

    val fetch: Fetch[Int] = (one(1), one(2), one(3)).mapN(_ + _ + _)
    val fut               = Fetch.run[Future](fetch)

    fut.map(_ shouldEqual 6)
  }

  "We can traverse over a list with a Fetch for each element" in {
    import cats.syntax.traverse._

    val fetch: Fetch[List[Int]] = for {
      manies <- many(3)
      ones   <- manies.traverse(one)
    } yield ones

    val fut = Fetch.run[Future](fetch)
    fut.map(_ shouldEqual List(0, 1, 2))
  }

  "Traversals are implicitly batched" in {
    import cats.syntax.traverse._

    val fetch: Fetch[List[Int]] = for {
      manies <- many(3)
      ones   <- manies.traverse(one)
    } yield ones

    Fetch
      .runEnv[Future](fetch)
      .map(env => {
        env.rounds.size shouldEqual 2
      })
  }

  "Fetch's custom traverse doesn't cause stack overflows for long lists" in {
    val length = 2000
    val fetch  = Fetch.traverse(List.range(0, length))(one)

    Fetch
      .runEnv[Future](fetch)
      .map { env =>
        env.rounds.size shouldEqual 1
      }
  }

  "Identities are deduped when batched" in {
    import cats.syntax.traverse._

    val manies = List(1, 1, 2)
    val fetch: Fetch[List[Int]] = for {
      ones <- manies.traverse(one)
    } yield ones

    Fetch
      .runEnv[Future](fetch)
      .map(env => {
        env.rounds.size shouldEqual 1
        env.rounds.head.request should matchPattern {
          case Concurrent(
              NonEmptyList(FetchMany(NonEmptyList(One(1), List(One(2))), source), Nil)) =>
        }
      })
  }

  "The product of two fetches implies parallel fetching" in {
    val fetch: Fetch[(Int, List[Int])] = Fetch.join(one(1), many(3))

    Fetch
      .runEnv[Future](fetch)
      .map(env => {
        env.rounds.size shouldEqual 1
      })
  }

  "Concurrent fetching calls batches only wen it can" in {
    val fetch: Fetch[(Int, List[Int])] = Fetch.join(one(1), many(3))

    Fetch
      .runEnv[Future](fetch)
      .map(env => {
        totalBatches(env.rounds) shouldEqual 0
      })
  }

  "If a fetch fails in the left hand of a product the product will fail" in {
    val fetch: Fetch[(Int, List[Int])] = Fetch.join(Fetch.error(DidNotFound()), many(3))
    val fut                            = Fetch.run[Future](fetch)

    ME.attempt(Fetch.run[Future](fetch)).map {
      case Left(UnhandledException(_, DidNotFound())) => assert(true)
      case _                                          => fail("Should've thrown NotFound exception")
    }
  }

  "If a fetch fails in the right hand of a product the product will fail" in {
    val fetch: Fetch[(List[Int], Int)] = Fetch.join(many(3), Fetch.error(DidNotFound()))
    val fut                            = Fetch.run[Future](fetch)

    ME.attempt(Fetch.run[Future](fetch)).map {
      case Left(UnhandledException(_, DidNotFound())) => assert(true)
      case _                                          => fail("Should've thrown NotFound exception")
    }
  }

  "If there is a missing identity in the left hand of a product the product will fail" in {
    val fetch: Fetch[(Int, List[Int])] = Fetch.join(Fetch(Never()), many(3))
    val fut                            = Fetch.run[Future](fetch)

    ME.attempt(Fetch.run[Future](fetch)).map {
      case Left(MissingIdentities(_, missing)) =>
        missing shouldEqual Map(NeverSource.name -> List(Never()))
      case _ => fail("Should've thrown a fetch failure")
    }
  }

  "If there is a missing identity in the right hand of a product the product will fail" in {
    val fetch: Fetch[(List[Int], Int)] = Fetch.join(many(3), Fetch(Never()))
    val fut                            = Fetch.run[Future](fetch)

    ME.attempt(fut).map {
      case Left(MissingIdentities(_, missing)) =>
        missing shouldEqual Map(NeverSource.name -> List(Never()))
      case _ => fail("Should've thrown a fetch failure")
    }
  }

  "The product of concurrent fetches implies everything fetched concurrently" in {
    val fetch = Fetch.join(
      Fetch.join(
        one(1),
        Fetch.join(one(2), one(3))
      ),
      one(4)
    )

    Fetch
      .runEnv[Future](fetch)
      .map(env => {
        val rounds = env.rounds
        val stats  = (rounds.size, totalBatches(rounds), totalFetched(rounds))

        stats shouldEqual (1, 1, 4)
      })
  }

  "The product of concurrent fetches of the same type implies everything fetched in a single batch" in {
    val fetch = Fetch.join(
      Fetch.join(
        for {
          a <- one(1)
          b <- one(2)
          c <- one(3)
        } yield c,
        for {
          a <- one(2)
          m <- many(4)
          c <- one(3)
        } yield c
      ),
      one(3)
    )

    Fetch
      .runEnv[Future](fetch)
      .map(env => {
        val rounds = env.rounds
        val stats  = (rounds.size, totalBatches(rounds), totalFetched(rounds))

        stats shouldEqual (2, 1, 4)
      })
  }

  "Every level of joined concurrent fetches is combined and batched" in {
    val fetch = Fetch.join(
      for {
        a <- one(2)
        b <- many(1)
        c <- one(5)
      } yield c,
      for {
        a <- one(3)
        b <- many(2)
        c <- one(4)
      } yield c
    )

    Fetch
      .runEnv[Future](fetch)
      .map(env => {
        val rounds = env.rounds
        val stats  = (rounds.size, totalBatches(rounds), totalFetched(rounds))

        stats shouldEqual (3, 3, 6)
      })
  }

  "Every level of sequenced concurrent fetches is batched" in {
    val fetch = Fetch.join(
      Fetch.join(
        for {
          a <- Fetch.sequence(List(one(2), one(3), one(4)))
          b <- Fetch.sequence(List(many(0), many(1)))
          c <- Fetch.sequence(List(one(9), one(10), one(11)))
        } yield c,
        for {
          a <- Fetch.sequence(List(one(5), one(6), one(7)))
          b <- Fetch.sequence(List(many(2), many(3)))
          c <- Fetch.sequence(List(one(12), one(13), one(14)))
        } yield c
      ),
      Fetch.sequence(List(one(15), one(16), one(17)))
    )

    Fetch
      .runEnv[Future](fetch)
      .map(env => {
        val rounds = env.rounds
        val stats  = (rounds.size, totalBatches(rounds), totalFetched(rounds))

        stats shouldEqual (3, 3, 9 + 4 + 6)
      })
  }

  "The product of two fetches from the same data source implies batching" in {
    val fetch: Fetch[(Int, Int)] = Fetch.join(one(1), one(3))

    Fetch
      .runEnv[Future](fetch)
      .map(env => {
        val rounds = env.rounds

        rounds.size shouldEqual 1
        totalBatches(rounds) shouldEqual 1
      })
  }
  "We can depend on previous computations of Fetch values" in {
    val fetch: Fetch[Int] = for {
      o <- one(1)
      t <- one(o + 1)
    } yield o + t

    Fetch.run[Future](fetch).map(_ shouldEqual 3)
  }

  "We can collect a list of Fetch into one" in {
    val sources: List[Fetch[Int]] = List(one(1), one(2), one(3))
    val fetch: Fetch[List[Int]]   = Fetch.sequence(sources)

    Fetch.run[Future](fetch).map(_ shouldEqual List(1, 2, 3))
  }

  "We can collect a list of Fetches with heterogeneous sources" in {
    val sources: List[Fetch[Int]] = List(one(1), one(2), one(3), anotherOne(4), anotherOne(5))
    val fetch: Fetch[List[Int]]   = Fetch.sequence(sources)

    Fetch.run[Future](fetch).map(_ shouldEqual List(1, 2, 3, 4, 5))
  }

  "Sequenced fetches are run concurrently" in {
    val sources: List[Fetch[Int]] = List(one(1), one(2), one(3), anotherOne(4), anotherOne(5))
    val fetch: Fetch[List[Int]]   = Fetch.sequence(sources)

    Fetch
      .runEnv[Future](fetch)
      .map(env => {
        val rounds = env.rounds

        rounds.size shouldEqual 1
        totalBatches(rounds) shouldEqual 2
      })
  }

  "Sequenced fetches are deduped" in {
    val sources: List[Fetch[Int]] = List(one(1), one(2), one(1))
    val fetch: Fetch[List[Int]]   = Fetch.sequence(sources)

    Fetch
      .runEnv[Future](fetch)
      .map(env => {
        val rounds = env.rounds

        rounds.size shouldEqual 1
        totalFetched(rounds) shouldEqual 2
      })
  }

  "Sequenced fetches are not asked for when cached" in {
    val sources: List[Fetch[Int]] = List(one(1), one(2), one(3), one(4))
    val fetch: Fetch[List[Int]]   = Fetch.sequence(sources)

    val fut = Fetch.runEnv[Future](
      fetch,
      InMemoryCache(
        OneSource.identity(One(1)) -> 1,
        OneSource.identity(One(2)) -> 2
      )
    )

    fut.map(env => {
      val rounds = env.rounds

      rounds.size shouldEqual 1
      totalFetched(rounds) shouldEqual 2
    })
  }

  "We can collect the results of a traversal" in {
    val fetch = Fetch.traverse(List(1, 2, 3))(one)

    Fetch.run[Future](fetch).map(_ shouldEqual List(1, 2, 3))
  }

  "Traversals are batched" in {
    val fetch = Fetch.traverse(List(1, 2, 3))(one)

    Fetch
      .runEnv[Future](fetch)
      .map(env => {
        env.rounds.size shouldEqual 1
      })
  }

  "Duplicated sources are only fetched once" in {
    val fetch = Fetch.traverse(List(1, 2, 1))(one)

    Fetch
      .runEnv[Future](fetch)
      .map(env => {
        val rounds = env.rounds

        rounds.size shouldEqual 1
        totalFetched(rounds) shouldEqual 2
      })
  }

  "Sources that can be fetched concurrently inside a for comprehension will be" in {
    val fetch = for {
      v      <- Fetch.pure(List(1, 2, 1))
      result <- Fetch.traverse(v)(one)
    } yield result

    Fetch
      .runEnv[Future](fetch)
      .map(env => {
        val rounds = env.rounds

        rounds.size shouldEqual 1
        totalFetched(rounds) shouldEqual 2
      })
  }

  "Elements are cached and thus not fetched more than once" in {
    val fetch = for {
      aOne       <- one(1)
      anotherOne <- one(1)
      _          <- one(1)
      _          <- one(2)
      _          <- one(3)
      _          <- one(1)
      _          <- Fetch.traverse(List(1, 2, 3))(one)
      _          <- one(1)
    } yield aOne + anotherOne

    Fetch
      .runEnv[Future](fetch)
      .map(env => {
        val rounds = env.rounds

        totalFetched(rounds) shouldEqual 3
      })
  }

  "Elements that are cached won't be fetched" in {
    val fetch = for {
      aOne       <- one(1)
      anotherOne <- one(1)
      _          <- one(1)
      _          <- one(2)
      _          <- one(3)
      _          <- one(1)
      _          <- Fetch.traverse(List(1, 2, 3))(one)
      _          <- one(1)
    } yield aOne + anotherOne

    val fut = Fetch.runEnv[Future](
      fetch,
      InMemoryCache(
        OneSource.identity(One(1)) -> 1,
        OneSource.identity(One(2)) -> 2,
        OneSource.identity(One(3)) -> 3
      )
    )

    fut.map(env => {
      val rounds = env.rounds

      rounds.size shouldEqual 0
    })
  }

  case class MyCache(state: Map[Any, Any] = Map.empty[Any, Any]) extends DataSourceCache {
    override def get[A](k: DataSourceIdentity): Option[A] = state.get(k).asInstanceOf[Option[A]]
    override def update[A](k: DataSourceIdentity, v: A): MyCache =
      copy(state = state.updated(k, v))
  }

  val fullCache: MyCache = MyCache(
    Map(
      OneSource.identity(One(1))   -> 1,
      OneSource.identity(One(2))   -> 2,
      OneSource.identity(One(3))   -> 3,
      OneSource.identity(One(1))   -> 1,
      ManySource.identity(Many(2)) -> List(0, 1)
    )
  )

  "We can use a custom cache" in {
    val fetch = for {
      aOne       <- one(1)
      anotherOne <- one(1)
      _          <- one(1)
      _          <- one(2)
      _          <- one(3)
      _          <- one(1)
      _          <- Fetch.traverse(List(1, 2, 3))(one)
      _          <- one(1)
    } yield aOne + anotherOne
    val fut = Fetch.runEnv[Future](
      fetch,
      InMemoryCache(
        OneSource.identity(One(1))   -> 1,
        OneSource.identity(One(2))   -> 2,
        OneSource.identity(One(3))   -> 3,
        ManySource.identity(Many(2)) -> List(0, 1)
      )
    )

    fut.map(env => {
      val rounds = env.rounds

      rounds.size shouldEqual 0
    })
  }

  case class ForgetfulCache() extends DataSourceCache {
    override def get[A](k: DataSourceIdentity): Option[A]               = None
    override def update[A](k: DataSourceIdentity, v: A): ForgetfulCache = this
  }

  "We can use a custom cache that discards elements" in {
    val fetch = for {
      aOne       <- one(1)
      anotherOne <- one(1)
      _          <- one(1)
      _          <- one(2)
      _          <- one(3)
      _          <- one(1)
      _          <- one(1)
    } yield aOne + anotherOne

    val fut = Fetch.runEnv[Future](fetch, ForgetfulCache())

    fut.map(env => {
      totalFetched(env.rounds) shouldEqual 7
    })
  }

  "We can use a custom cache that discards elements together with concurrent fetches" in {
    val fetch = for {
      aOne       <- one(1)
      anotherOne <- one(1)
      _          <- one(1)
      _          <- one(2)
      _          <- Fetch.traverse(List(1, 2, 3))(one)
      _          <- one(3)
      _          <- one(1)
      _          <- one(1)
    } yield aOne + anotherOne

    val fut = Fetch.runEnv[Future](fetch, ForgetfulCache())

    fut.map(env => {
      totalFetched(env.rounds) shouldEqual 10
    })
  }

  "We can fetch multiple items at the same time" in {
    val fetch: Fetch[List[Int]] = Fetch.multiple(One(1), One(2), One(3))
    Fetch.runFetch[Future](fetch).map {
      case (env, res) =>
        res shouldEqual List(1, 2, 3)
        totalFetched(env.rounds) shouldEqual 3
        totalBatches(env.rounds) shouldEqual 1
        env.rounds.size shouldEqual 1
    }
  }

  "Pure Fetches should be ignored in the parallel optimization" in {
    val fetch: Fetch[(Int, Int)] = Fetch.join(
      one(1),
      for {
        a <- Fetch.pure(2)
        b <- one(3)
      } yield a + b
    )

    Fetch.runFetch[Future](fetch).map {
      case (env, res) =>
        res shouldEqual (1, 5)
        totalFetched(env.rounds) shouldEqual 2
        env.rounds.size shouldEqual 1
    }
  }
}
