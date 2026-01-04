package com.ramapay.app.chat.service

import com.ramapay.app.entity.Wallet
import com.ramapay.app.repository.TransactionRepositoryType
import io.reactivex.Single
import io.reactivex.schedulers.Schedulers
import org.web3j.protocol.Web3j
import org.web3j.protocol.core.DefaultBlockParameterName
import org.web3j.protocol.http.HttpService
import timber.log.Timber
import java.math.BigInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Service to detect and clear stuck transactions by sending replacement transactions.
 * 
 * When transactions get stuck (pending but not confirmed), this service:
 * 1. Detects the number of stuck transactions by comparing confirmed vs pending nonce
 * 2. Sends replacement 0-value self-transfers with higher gas to replace each stuck tx
 * 3. Waits for confirmations
 */
@Singleton
class NonceClearService @Inject constructor(
    private val transactionRepository: TransactionRepositoryType
) {
    companion object {
        private const val RAMESTTA_RPC = "https://blockchain.ramestta.com"
        private const val RAMESTTA_CHAIN_ID = 1370L
        private const val GAS_MULTIPLIER = 2.0 // Double the gas price for replacement txs
        private const val GAS_LIMIT = 21000L // Standard transfer gas limit
        private val DEFAULT_GAS_PRICE = BigInteger.valueOf(7_000_000_000L) // 7 gwei fallback
    }
    
    data class NonceStatus(
        val confirmedNonce: Long,
        val pendingNonce: Long,
        val stuckCount: Int
    ) {
        val hasStuckTransactions: Boolean = stuckCount > 0
    }
    
    data class ClearResult(
        val success: Boolean,
        val clearedCount: Int,
        val failedAt: Long? = null,
        val error: String? = null
    )
    
    /**
     * Check if there are stuck transactions for the given wallet address.
     */
    fun checkNonceStatus(walletAddress: String): Single<NonceStatus> {
        return Single.fromCallable {
            val web3j = Web3j.build(HttpService(RAMESTTA_RPC))
            
            try {
                // Get confirmed nonce (latest)
                val confirmedNonce = web3j
                    .ethGetTransactionCount(walletAddress, DefaultBlockParameterName.LATEST)
                    .send()
                    .transactionCount
                    .longValueExact()
                
                // Get pending nonce (includes pending txs)
                val pendingNonce = web3j
                    .ethGetTransactionCount(walletAddress, DefaultBlockParameterName.PENDING)
                    .send()
                    .transactionCount
                    .longValueExact()
                
                val stuckCount = (pendingNonce - confirmedNonce).toInt().coerceAtLeast(0)
                
                Timber.d("NonceClearService: confirmed=$confirmedNonce, pending=$pendingNonce, stuck=$stuckCount")
                
                NonceStatus(
                    confirmedNonce = confirmedNonce,
                    pendingNonce = pendingNonce,
                    stuckCount = stuckCount
                )
            } finally {
                web3j.shutdown()
            }
        }.subscribeOn(Schedulers.io())
    }
    
    /**
     * Clear stuck transactions by sending replacement self-transfers.
     * Each replacement tx sends 0 value to self with higher gas.
     */
    fun clearStuckTransactions(wallet: Wallet): Single<ClearResult> {
        return checkNonceStatus(wallet.address)
            .flatMap { status ->
                if (!status.hasStuckTransactions) {
                    Single.just(ClearResult(success = true, clearedCount = 0))
                } else {
                    clearNonces(wallet, status.confirmedNonce, status.stuckCount)
                }
            }
    }
    
    private fun clearNonces(wallet: Wallet, startNonce: Long, count: Int): Single<ClearResult> {
        return Single.fromCallable {
            Timber.d("NonceClearService: Clearing $count stuck nonces starting at $startNonce")
            
            var clearedCount = 0
            var failedNonce: Long? = null
            var errorMessage: String? = null
            
            // Get current gas price from blockchain
            val web3j = Web3j.build(HttpService(RAMESTTA_RPC))
            val baseGasPrice = try {
                web3j.ethGasPrice().send().gasPrice
            } catch (e: Exception) {
                Timber.w(e, "Failed to get gas price, using default")
                DEFAULT_GAS_PRICE
            }
            
            val replacementGasPrice = baseGasPrice.multiply(BigInteger.valueOf(2)) // Double the gas price
            
            Timber.d("NonceClearService: Using gas price: $replacementGasPrice")
            
            try {
                for (nonce in startNonce until (startNonce + count)) {
                    try {
                        Timber.d("NonceClearService: Clearing nonce $nonce")
                        
                        // Send a 0-value self-transfer to replace the stuck tx
                        val txHash = transactionRepository.resendTransaction(
                            wallet,
                            wallet.address, // To self
                            BigInteger.ZERO, // 0 value
                            BigInteger.valueOf(nonce),
                            replacementGasPrice,
                            BigInteger.valueOf(GAS_LIMIT),
                            ByteArray(0), // Empty data
                            RAMESTTA_CHAIN_ID
                        ).blockingGet()
                        
                        Timber.d("NonceClearService: Cleared nonce $nonce, tx: $txHash")
                        clearedCount++
                        
                        // Small delay between transactions
                        Thread.sleep(500)
                        
                    } catch (e: Exception) {
                        // Check if "nonce too low" which means it's already confirmed
                        if (e.message?.contains("nonce too low", ignoreCase = true) == true ||
                            e.message?.contains("already known", ignoreCase = true) == true) {
                            Timber.d("NonceClearService: Nonce $nonce already confirmed or known")
                            clearedCount++
                        } else {
                            Timber.e(e, "NonceClearService: Failed to clear nonce $nonce")
                            failedNonce = nonce
                            errorMessage = e.message
                            break
                        }
                    }
                }
            } finally {
                web3j.shutdown()
            }
            
            ClearResult(
                success = failedNonce == null,
                clearedCount = clearedCount,
                failedAt = failedNonce,
                error = errorMessage
            )
        }.subscribeOn(Schedulers.io())
    }
}
