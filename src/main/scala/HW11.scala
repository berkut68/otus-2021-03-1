package me.chuwy.otusfp.home

import cats.data.Validated
import cats.effect._
import fs2.io.file.Files
import io.circe.generic.auto._
import org.http4s.HttpRoutes
import org.http4s.blaze.server.BlazeServerBuilder
import org.http4s.circe.CirceEntityCodec.circeEntityEncoder
import org.http4s.dsl.Http4sDsl
import org.http4s.implicits._
import org.http4s.server.Router
import java.nio.file._

import scala.concurrent.ExecutionContext.global
import cats.data.{Kleisli, OptionT, ReaderT}
import fs2.Chunk
import org.http4s.Response

import java.nio.charset.StandardCharsets
import cats.effect.{IO, Ref, _}
import java.util.concurrent.TimeUnit
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.duration.DurationInt
import fs2.Stream

import scala.concurrent.duration.DurationInt

object HW11 {
  case class counter(counter: Long)

  def slowStream(chunk: Int, total: Int, time: Int): Stream[IO, String] = {
    Stream.emit(1)
      .repeatN(total)
      .chunkN(chunk)
      .metered[IO](FiniteDuration(time, TimeUnit.SECONDS))
      .evalMapChunk {
        chunk: Chunk[Int] =>
          IO.pure(chunk.toList.mkString)
      }

  }

  def routes(ref: Ref[IO, Long]): HttpRoutes[IO] = {
    val dsl = Http4sDsl[IO]
    import dsl._

    HttpRoutes.of[IO] {
      case GET -> Root / "counter" =>
        ref.getAndUpdate(_ + 1).flatMap(r => Ok(counter(r)))

      case GET -> Root / "slow" / IntVar(chunk) / IntVar(total) / IntVar(time) =>
        val response: IO[Response[IO]] = for {

          chunkSize <- IO.fromOption(Some(chunk).filter(_ > 0))(new Throwable("Bad request (invalid chunk value)"))
          totalSize <- IO.fromOption(Some(total).filter(_ > 0))(new Throwable("Bad request (invalid total value)"))
          delay     <- IO.fromOption(Some(time).filter(_ > 0))(new Throwable("Bad request (wrong time value)"))

          result <- Ok(slowStream(totalSize, chunkSize, delay))
        } yield result

        response.attempt.flatMap {
          case Left(error) => BadRequest(error.getMessage)
          case Right(value) => IO.pure(value)
        }
    }
  }

  def router(ref: Ref[IO, Long]): HttpRoutes[IO] = Router("/" -> routes(ref))

  def server(ref: Ref[IO, Long]): BlazeServerBuilder[IO] = {
    BlazeServerBuilder[IO](scala.concurrent.ExecutionContext.global)
      .bindHttp(8080, "localhost")
      .withHttpApp(router(ref).orNotFound)
  }
}

object CounterServer extends IOApp.Simple {
  override def run: IO[Unit] = {
    val refio: IO[Ref[IO, Long]] = Ref[IO].of(0L)
    refio.flatMap(ref => HW11.server(ref).resource.use(_ => IO.never))
  }
}