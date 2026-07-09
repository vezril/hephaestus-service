package me.cference.hephaestus.apollo

import io.grpc.{Status, StatusRuntimeException}
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

/**
 * Pure classification tests: a gRPC status maps to the right [[ApolloError]] case with the correct
 * `retriable` flag. UNAVAILABLE/DEADLINE_EXCEEDED are retriable; NOT_FOUND, FAILED_PRECONDITION,
 * INVALID_ARGUMENT, and anything unclassified are terminal.
 */
final class ApolloErrorSpec extends AnyFunSuite with Matchers:

  private def sre(code: Status.Code): StatusRuntimeException =
    new StatusRuntimeException(code.toStatus.withDescription(s"$code detail"))

  test("UNAVAILABLE is retriable") {
    val e = ApolloError.classify("op", sre(Status.Code.UNAVAILABLE))
    e shouldBe a[ApolloError.Unavailable]
    e.retriable shouldBe true
  }

  test("DEADLINE_EXCEEDED is retriable") {
    val e = ApolloError.classify("op", sre(Status.Code.DEADLINE_EXCEEDED))
    e shouldBe a[ApolloError.DeadlineExceeded]
    e.retriable shouldBe true
  }

  test("NOT_FOUND is terminal") {
    val e = ApolloError.classify("op", sre(Status.Code.NOT_FOUND))
    e shouldBe a[ApolloError.NotFound]
    e.retriable shouldBe false
  }

  test("FAILED_PRECONDITION (checksum) is terminal") {
    val e = ApolloError.classify("op", sre(Status.Code.FAILED_PRECONDITION))
    e shouldBe a[ApolloError.FailedPrecondition]
    e.retriable shouldBe false
  }

  test("INVALID_ARGUMENT is terminal") {
    val e = ApolloError.classify("op", sre(Status.Code.INVALID_ARGUMENT))
    e shouldBe a[ApolloError.InvalidArgument]
    e.retriable shouldBe false
  }

  test("an unclassified status is conservatively terminal") {
    val e = ApolloError.classify("op", sre(Status.Code.PERMISSION_DENIED))
    e shouldBe a[ApolloError.Unexpected]
    e.retriable shouldBe false
  }

  test("an already-typed ApolloError passes through unchanged") {
    val original = ApolloError.Md5Mismatch("b", "k", "aa", "bb")
    ApolloError.classify("op", original) should be theSameInstanceAs original
  }

  test("a non-status throwable is terminal (Unexpected)") {
    val e = ApolloError.classify("op", new RuntimeException("boom"))
    e shouldBe a[ApolloError.Unexpected]
    e.retriable shouldBe false
  }
