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

package fetch.twitterFuture

import com.twitter.util.{Future => TwitterFuture, Return, Throw}
import scala.concurrent.{Future => ScalaFuture, Promise => ScalaPromise, ExecutionContext}

object Convert {

  /** https://twitter.github.io/util/guide/util-cookbook/futures.html */
  def twitterToScalaFuture[A](tf: TwitterFuture[A])(
      implicit ec: ExecutionContext): ScalaFuture[A] = {
    val promise: ScalaPromise[A] = ScalaPromise()
    tf.respond {
      case Return(value)    => promise.trySuccess(value)
      case Throw(exception) => promise.tryFailure(exception)
    }
    promise.future
  }

}
