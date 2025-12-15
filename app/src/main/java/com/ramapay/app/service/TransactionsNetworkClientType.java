package com.ramapay.app.service;

import com.ramapay.app.entity.NetworkInfo;
import com.ramapay.app.entity.Transaction;
import com.ramapay.app.entity.TransactionMeta;
import com.ramapay.app.entity.transactionAPI.TransferFetchType;
import com.ramapay.app.entity.transactions.TransferEvent;

import java.util.List;
import java.util.Map;

import io.reactivex.Single;

public interface TransactionsNetworkClientType
{
    Single<Transaction[]> storeNewTransactions(TokensService svs, NetworkInfo networkInfo, String tokenAddress, long lastBlock);

    Single<TransactionMeta[]> fetchMoreTransactions(TokensService svs, NetworkInfo network, long lastTxTime);

    Single<Map<String, List<TransferEvent>>> readTransfers(String currentAddress, NetworkInfo networkByChain, TokensService tokensService, TransferFetchType tfType);

    void checkRequiresAuxReset(String walletAddr);

    /**
     * Force fetch the latest transactions from the API and store them locally.
     * This fetches both sent and received transactions with pagination.
     * @param svs TokensService
     * @param networkInfo Network to fetch from
     * @param walletAddress The wallet address
     * @param limit Number of transactions to fetch (default 20)
     * @return Array of fetched transactions
     */
    Single<Transaction[]> fetchLatestTransactionsFromApi(TokensService svs, NetworkInfo networkInfo, String walletAddress, int limit);
}
