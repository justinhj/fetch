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

package fetch.unsafe

import fetch._

import cats.{Eval, FlatMap, Id}
import cats.syntax.either._

import scala.concurrent._
import scala.concurrent.duration._

object implicits {
  implicit def evalFetchMonadError(
      implicit FM: FlatMap[Eval]
  ): FetchMonadError[Eval] = new FetchMonadError[Eval] {
    override def runQuery[A](j: Query[A]): Eval[A] = j match {
      case Sync(e)    => e
      case Ap(qf, qx) => ap(runQuery(qf))(runQuery(qx))
      case Async(action, timeout) =>
        Eval.later {
          val latch                                  = new java.util.concurrent.CountDownLatch(1)
          @volatile var result: Either[Throwable, A] = null
          new Thread(new Runnable {
            def run() = {
              action(a => {
                result = Either.right(a);
                latch.countDown
              }, err => {
                result = Either.left(err);
                latch.countDown
              })
            }
          }).start()
          latch.await
          result match {
            case Left(err) => throw err
            case Right(v)  => v
          }
        }
    }
    def pure[A](x: A): Eval[A] = Eval.now(x)

    def tailRecM[A, B](a: A)(f: A => Eval[Either[A, B]]): Eval[B] = FM.tailRecM(a)(f)

    def handleErrorWith[A](fa: Eval[A])(f: FetchException => Eval[A]): Eval[A] =
      Eval.later({
        try {
          fa.value
        } catch {
          case ex: FetchException => f(ex).value
        }
      })

    def raiseError[A](e: FetchException): Eval[A] =
      Eval.later({
        throw e
      })

    def flatMap[A, B](fa: Eval[A])(f: A => Eval[B]): Eval[B] =
      fa.flatMap(f)
  }

  implicit def idFetchMonadError(
      implicit FM: FlatMap[Id]
  ): FetchMonadError[Id] = new FetchMonadError[Id] {
    override def runQuery[A](j: Query[A]): Id[A] = j match {
      case Sync(e)    => e.value
      case Ap(qf, qx) => ap(runQuery(qf))(runQuery(qx))
      case Async(action, timeout) => {
        val latch                                  = new java.util.concurrent.CountDownLatch(1)
        @volatile var result: Either[Throwable, A] = null
        new Thread(new Runnable {
          def run() = {
            action(a => {
              result = Either.right(a);
              latch.countDown
            }, err => {
              result = Either.left(err);
              latch.countDown
            })
          }
        }).start()
        latch.await
        result match {
          case Left(err) => throw err
          case Right(v)  => v
        }
      }
    }
    def pure[A](x: A): Id[A]                                  = x
    def tailRecM[A, B](a: A)(f: A => Id[Either[A, B]]): Id[B] = FM.tailRecM(a)(f)

    def handleErrorWith[A](fa: Id[A])(f: FetchException => Id[A]): Id[A] =
      try {
        fa
      } catch {
        case ex: FetchException => f(ex)
      }
    def raiseError[A](e: FetchException): Id[A] =
      e match {
        case UnhandledException(_, ex) => {
          e.initCause(ex)
          throw e
        }
        case other => throw other
      }
    def flatMap[A, B](fa: Id[A])(f: A => Id[B]): Id[B] = f(fa)
  }
}
