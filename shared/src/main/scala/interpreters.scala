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

import cats.~>
import cats.free.Free
import cats.implicits._

import fetch.interpreters._

private[fetch] trait FetchInterpreters {

  def interpreter[M[_]: FetchMonadError]: FetchOp ~> FetchInterpreter[M]#f =
    ParallelJoinPhase.apply
      .andThen[Fetch](Free.foldMap(MaxBatchSizePhase.apply))
      .andThen[FetchInterpreter[M]#f](
        Free.foldMap[FetchOp, FetchInterpreter[M]#f](CoreInterpreter[M]))

}
