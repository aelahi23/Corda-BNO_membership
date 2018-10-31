package net.corda.businessnetworks.membership

import co.paralleluniverse.fibers.Suspendable
import net.corda.businessnetworks.membership.bno.OnMembershipChanged
import net.corda.businessnetworks.membership.bno.service.BNOConfigurationService
import net.corda.businessnetworks.membership.member.support.BusinessNetworkAwareInitiatedFlow
import net.corda.businessnetworks.membership.states.MembershipContract
import net.corda.core.flows.FlowLogic
import net.corda.core.flows.FlowSession
import net.corda.core.identity.CordaX500Name
import net.corda.core.identity.Party
import net.corda.core.transactions.SignedTransaction
import net.corda.core.utilities.getOrThrow
import net.corda.core.utilities.unwrap
import net.corda.testing.node.StartedMockNode
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

class ActivateMembershipFlowTest : AbstractFlowTest(
        numberOfBusinessNetworks = 2,
        numberOfParticipants = 4,
        participantRespondingFlows = listOf(NotificationsCounterFlow::class.java)) {

    private fun testMembershipActivation(activateCallback : (bnoNode : StartedMockNode, participantNode : StartedMockNode) -> SignedTransaction) {
        val bnoNode = bnoNodes.first()
        val participantNode = participantsNodes.first()

        runRequestMembershipFlow(bnoNode, participantNode)

        // membership state before activation
        val inputMembership = getMembership(participantNode, participantNode.identity())

        val stx = activateCallback(bnoNode, participantNode)
        stx.verifyRequiredSignatures()

        val outputTxState = stx.tx.outputs.single()
        val command = stx.tx.commands.single()

        assert(MembershipContract.CONTRACT_NAME == outputTxState.contract)
        assert(command.value is MembershipContract.Commands.Activate)
        assert(stx.tx.inputs.single() == inputMembership.ref)

        // making sure that a correct notification has been send
        val membershipStateAndRef = getMembership(bnoNode, participantNode.identity())
        val notification = NotificationsCounterFlow.NOTIFICATIONS.single()
        assertEquals(NotificationHolder(participantNode.identity(), bnoNode.identity(), OnMembershipChanged(membershipStateAndRef)), notification)
    }

    @Test
    fun `membership activation happy path`() = testMembershipActivation { bnoNode, participantNode ->
        runActivateMembershipFlow(bnoNode, participantNode.identity())
    }

    @Test
    fun `membership activation should succeed when using a convenience flow`() = testMembershipActivation { bnoNode, participantNode ->
        runActivateMembershipForPartyFlow(bnoNode, participantNode.identity())
    }

    @Test
    fun `nodes should be able to transact on the business networks where their membership is active`() {
        val bno1Node = bnoNodes[0]
        val bno2Node = bnoNodes[1]

        val multiBnNode= participantsNodes[0] // participates on both of the BNs
        val bn1ParticipantNode = participantsNodes[1] // participates in BN1 only
        val bn2ParticipantNode = participantsNodes[2] // participates in BN2 only

        // registering BN-specific initiated flows
        bn1ParticipantNode.registerInitiatedFlow(BN_1_RespondingFlow::class.java)
        bn2ParticipantNode.registerInitiatedFlow(BN_2_RespondingFlow::class.java)

        // activating multiBnNode's node membership in BN1 only
        runrequestAndActivateMembershipFlow(bno1Node, multiBnNode)
        runRequestMembershipFlow(bno2Node, multiBnNode)
        runrequestAndActivateMembershipFlow(bno1Node, bn1ParticipantNode)
        runrequestAndActivateMembershipFlow(bno2Node, bn2ParticipantNode)

        val future1 = multiBnNode.startFlow(BN_1_InitiatingFlow(bn1ParticipantNode.identity()))
        val future2 = multiBnNode.startFlow(BN_2_InitiatingFlow(bn2ParticipantNode.identity()))

        mockNetwork.runNetwork()

        future1.getOrThrow()
        // future 2 should fail as multiBnNode's membership is not active in BN2
        try {
            future2.getOrThrow()
            fail()
        } catch (ex : NotAMemberException) {
        }
    }

    @Test
    fun `only BNO should be able to start the flow`() {
        val bnoNode = bnoNodes.first()
        val participantNode = participantsNodes.first()

        runRequestMembershipFlow(bnoNode, participantNode)
        try {
            runActivateMembershipFlow(participantNode, participantNode.identity())
            fail()
        } catch (e : NotBNOException) {
            assertEquals("This node is not the business network operator for this membership", e.message)
        }
    }

    @Test
    fun `membership can be auto activated`() {
        val bnoNode = bnoNodes.first()
        val bnoConfiguration = bnoNode.services.cordaService(BNOConfigurationService::class.java)
        bnoConfiguration.reloadConfigurationFromFile(fileFromClasspath("membership-service-with-auto-approver.conf"))
        val participantNode = participantsNodes.first()

        runRequestMembershipFlow(bnoNode, participantNode)

        // membership state before activation
        val inputMembership = getMembership(participantNode, participantNode.identity())
        assertTrue(inputMembership.state.data.isActive())

        val updatedMembership = getMembership(bnoNode, participantNode.identity())
        assertEquals(NotificationHolder(participantNode.identity(), bnoNode.identity(), OnMembershipChanged(updatedMembership)), NotificationsCounterFlow.NOTIFICATIONS.single())
    }
}

open class AbstractDummyInitiatingFlow(private val counterparty : Party) : FlowLogic<Unit>() {
    @Suspendable
    override fun call() {
        initiateFlow(counterparty).sendAndReceive<String>("Hello")
    }
}

open class AbstractBNAwareRespondingFlow(session : FlowSession, private val bnoName : String) : BusinessNetworkAwareInitiatedFlow<Unit>(session)  {
    override fun bnoIdentity()  = serviceHub.identityService.wellKnownPartyFromX500Name(CordaX500Name.parse(bnoName))!!

    @Suspendable
    override fun onOtherPartyMembershipVerified() {
        session.receive<String>().unwrap { it }
        session.send("Hello")
    }
}