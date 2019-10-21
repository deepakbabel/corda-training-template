package net.corda.training.flow

import co.paralleluniverse.fibers.Suspendable
import net.corda.core.contracts.Command
import net.corda.core.contracts.requireThat
import net.corda.core.flows.*
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.training.contract.IOUContract
import net.corda.training.state.IOUState

/**
 * This is the flow which handles issuance of new IOUs on the ledger.
 * Gathering the counterparty's signature is handled by the [CollectSignaturesFlow].
 * Notarisation (if required) and commitment to the ledger is handled by the [FinalityFlow].
 * The flow returns the [SignedTransaction] that was committed to the ledger.
 */
@InitiatingFlow
@StartableByRPC
class IOUIssueFlow(val state: IOUState) : FlowLogic<SignedTransaction>() {
    @Suspendable
    override fun call(): SignedTransaction {

        //Get the notary
        val notary = serviceHub.networkMapCache.notaryIdentities.first()

        //Create a transactionBuilder
        val transactionBuilder: TransactionBuilder = TransactionBuilder()

        //Assign the notary
        transactionBuilder.notary = notary

        //Create the issue command
        val issueCommand= Command(IOUContract.Commands.Issue(), state.participants.map{it.owningKey})

        //No existing input state as we are creating an IOU for the first time

        //Add the iou as the output state
        transactionBuilder.addOutputState(state, IOUContract.IOU_CONTRACT_ID)

        //Add the command to the transaction
        transactionBuilder.addCommand(issueCommand)

        //Verify and sign it with our keypair
        transactionBuilder.verify(serviceHub)
        val partialTx = serviceHub.signInitialTransaction(transactionBuilder)

        //initiate all other flows than our own flow which will verify this transaction.
        val sessions = (state.participants - ourIdentity).map { initiateFlow(it) }.toSet()

        //Execute a common subFlow for collecting signatures by passing our signed transaction proposal and the other sessions
        val signedTx = subFlow(CollectSignaturesFlow(partialTx, sessions))

        //Now both Lender & borrower have signed.
        // So, Get the notary to sign this transaction and let the first party send the broadcast to all other sessions
        return subFlow(FinalityFlow(signedTx, sessions))
        // Placeholder code to avoid type error when running the tests. Remove before starting the flow task!
//        return serviceHub.signInitialTransaction(
//                TransactionBuilder(notary = null)
//        )
    }
}

/**
 * This is the flow which signs IOU issuances.
 * The signing is handled by the [SignTransactionFlow].
 */
@InitiatedBy(IOUIssueFlow::class)
class IOUIssueFlowResponder(val flowSession: FlowSession): FlowLogic<SignedTransaction>() {
    @Suspendable
    override fun call(): SignedTransaction {
        val signedTransactionFlow = object : SignTransactionFlow(flowSession) {
            override fun checkTransaction(stx: SignedTransaction) = requireThat {
                val output = stx.tx.outputs.single().data
                "This must be an IOU transaction" using (output is IOUState)
            }
        }
        val txWeJustSignedId = subFlow(signedTransactionFlow)
        return subFlow(ReceiveFinalityFlow(otherSideSession = flowSession, expectedTxId = txWeJustSignedId.id))
        //subFlow(signedTransactionFlow)
    }
}