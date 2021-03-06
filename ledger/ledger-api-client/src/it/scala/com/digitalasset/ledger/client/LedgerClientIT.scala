// Copyright (c) 2020 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.ledger.client

import com.daml.ledger.api.domain
import com.daml.ledger.api.testing.utils.{AkkaBeforeAndAfterAll, SuiteResourceManagementAroundEach}
import com.daml.ledger.client.configuration.{
  CommandClientConfiguration,
  LedgerClientConfiguration,
  LedgerIdRequirement
}
import com.daml.lf.data.Ref
import com.daml.platform.common.LedgerIdMode
import com.daml.platform.sandbox.config.SandboxConfig
import com.daml.platform.sandboxnext.SandboxNextFixture
import io.grpc.ManagedChannel
import org.scalatest.{AsyncWordSpec, Inside, Matchers}
import scalaz.OneAnd

final class LedgerClientIT
    extends AsyncWordSpec
    with Matchers
    with Inside
    with AkkaBeforeAndAfterAll
    with SuiteResourceManagementAroundEach
    with SandboxNextFixture {

  private val LedgerId =
    domain.LedgerId(s"${classOf[LedgerClientIT].getSimpleName.toLowerCase}-ledger-id")

  private val ClientConfiguration = LedgerClientConfiguration(
    applicationId = classOf[LedgerClientIT].getSimpleName,
    ledgerIdRequirement = LedgerIdRequirement.none,
    commandClient = CommandClientConfiguration.default,
    sslContext = None,
    token = None,
  )

  override protected def config: SandboxConfig = super.config.copy(
    ledgerIdMode = LedgerIdMode.Static(LedgerId),
  )

  "the ledger client" should {
    "retrieve the ledger ID" in {
      for {
        client <- LedgerClient(channel, ClientConfiguration)
      } yield {
        client.ledgerId should be(LedgerId)
      }
    }

    "make some requests" in {
      val partyName = "Alice"
      for {
        client <- LedgerClient(channel, ClientConfiguration)
        // The request type is irrelevant here; the point is that we can make some.
        allocatedParty <- client.partyManagementClient
          .allocateParty(hint = Some(partyName), displayName = None)
        retrievedParties <- client.partyManagementClient
          .getParties(OneAnd(Ref.Party.assertFromString(partyName), Set.empty))
      } yield {
        retrievedParties should be(List(allocatedParty))
      }
    }

    "shut down the channel when closed" in {
      for {
        client <- LedgerClient(channel, ClientConfiguration)
      } yield {
        inside(channel) {
          case channel: ManagedChannel =>
            channel.isShutdown should be(false)
            channel.isTerminated should be(false)

            client.close()

            channel.isShutdown should be(true)
            channel.isTerminated should be(true)
        }
      }
    }
  }
}
