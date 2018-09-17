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

import scala.collection.immutable._

/**
 * An environment that is passed along during the fetch rounds. It holds the
 * cache and the list of rounds that have been executed.
 */
trait Env {
  def rounds: Seq[Round]
  def cache: DataSourceCache
  def evolve(newRound: Round, newCache: DataSourceCache): Env
}

/**
 * A data structure that holds information about a fetch round.
 */
case class Round(
    cache: DataSourceCache,
    request: FetchRequest,
    response: Any,
    start: Long,
    end: Long
) {
  def duration: Double = (end - start) / 1e6
}

/**
 * A concrete implementation of `Env` used in the default Fetch interpreter.
 */
case class FetchEnv(
    cache: DataSourceCache,
    rounds: Queue[Round] = Queue.empty
) extends Env {

  def evolve(
      newRound: Round,
      newCache: DataSourceCache
  ): FetchEnv =
    copy(rounds = rounds :+ newRound, cache = newCache)
}
