package com.nailton.inpurchaseapp.helper

import android.app.Activity
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClient.BillingResponseCode
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ConsumeParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesResponseListener
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.android.billingclient.api.consumePurchase
import com.android.billingclient.api.queryPurchasesAsync
import com.google.common.collect.ImmutableList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Data class helper que ajudara a activity principal a processar o billingClient,
 * como as activitys dependendem do clico de vida da aplicacao criamos um data class
 * que tera todas as informacoes de compra do app a serem processadas pela activity
 * separando o codigo de negocio da tela.
 */

data class PurchaseHelper(val activity: Activity) {
    private val coroutineScope = CoroutineScope(Dispatchers.IO)
    private lateinit var  billingClient: BillingClient
    private lateinit var productDetails: ProductDetails
    private lateinit var purchase: Purchase
    private val demoProductId = "3097728748927984792"

    private val _productName = MutableStateFlow("Searching...")
    val productName = _productName.asStateFlow()

    private val _buyEnabled = MutableStateFlow(false)
    val buyEnabled = _buyEnabled.asStateFlow()

    private val _consumeEnabled = MutableStateFlow(false)
    val consumeEnabled = _consumeEnabled.asStateFlow()

    private val _statusText = MutableStateFlow("Initializing...")
    val statusText = _statusText.asStateFlow()


    // criando metodo que sera chamado na activity e iniciare um billingClient
    fun billingSetup() {
        // iniciando build do billingClient
        billingClient = BillingClient.newBuilder(activity)
            .setListener(purchasesUpdateListener)
            .enablePendingPurchases()
            .build()

        /**
         * comecando conexao com o billing, caso o servico se desconect a funcao onBillingServiceDisconnected
         * sera chamada acarretando na perda de conexao, caso seja finalizada com um OK conectaremos o produto
         * e caso a conexao seja perdida antes de finalizar avisamos que a conexao falhou
         */
        billingClient.startConnection(object: BillingClientStateListener {
            override fun onBillingServiceDisconnected() {
                _statusText.value = "Billing Client Connection Lost"
            }

            override fun onBillingSetupFinished(response: BillingResult) {
                if (response.responseCode == BillingClient.BillingResponseCode.OK) {
                    _statusText.value = "Billing Client Connected"
                    queryProduct(demoProductId)
                    reloadPurchase()
                } else {
                    _statusText.value = "Billing Client Connection Failure"
                }
            }

        })
    }

    fun queryProduct(productId: String) {
        // Iniciando builder de produtos, passando uma lista imutavel de produtos com o id do produto
        // cadastrado para a venda, caso tenhamos mais de um produto fazer um map e iterar sobre os ids
        val queryProductDetailsParams = QueryProductDetailsParams.newBuilder()
            .setProductList(
                ImmutableList.of(
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(productId)
                        .setProductType(BillingClient.ProductType.INAPP)
                        .build()
                )
            )
            .build()

        // iniciando query async para recuperar detalhes dos produtos, caso seja empty retornaremos
        // uma mensgaem no statusText e o buyEnabled como false e caso tenhamos produtos acessaremos
        // o primeiro produto da lista e retornaremos
        billingClient.queryProductDetailsAsync(queryProductDetailsParams) { _, produtDetailsList ->
            if (produtDetailsList.isEmpty()) {
                _statusText.value = "No Matching Products Found"
                _buyEnabled.value = false
            } else {
                productDetails = produtDetailsList[0]
                _productName.value = "Product: "+productDetails.name
            }
        }
    }

    // adcionando manipular de atualizacoes de vendas
    private val purchasesUpdateListener = PurchasesUpdatedListener { billingResult, purchases ->  
        if (billingResult.responseCode == BillingResponseCode.OK && purchases != null) {
            for (purchase in purchases) {
                completePurchase(purchase)
            }
        } else if (billingResult.responseCode == BillingResponseCode.USER_CANCELED) {
            _statusText.value = "Purchase Canceled"
        } else {
            _statusText.value = "Purchase Error"
        }
    }

    private fun completePurchase(item: Purchase) {
        purchase = item

        if (purchase.purchaseState == Purchase.PurchaseState.PURCHASED) {
            _buyEnabled.value = false
            _consumeEnabled.value = true
            _statusText.value = "Purchase Completed"
        }
    }

    // adcionando metodo que sera chamado quando clicarmos no botao de compra
    fun makePurchase() {
        val billingFlowParams = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(
                ImmutableList.of(
                    BillingFlowParams.ProductDetailsParams.newBuilder()
                        .setProductDetails(productDetails)
                        .build()
                )
            )
            .build()
        billingClient.launchBillingFlow(activity, billingFlowParams)
    }

    // apos o usuario clicar no botao de compra do app que ira chamar a funcao makePurchase,
    // temos que garantir que caso ocorra um segundo toque a funcao de compra nao seja chamada mais uma vez
    // para isso adcionaremos a funcao consumerPurchase
    fun consumerPurchase() {
        val consumeParams = ConsumeParams.newBuilder()
            .setPurchaseToken(purchase.purchaseToken)
            .build()
        coroutineScope.launch {
            val result = billingClient.consumePurchase(consumeParams)
            if (result.billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                _statusText.value = "Purchase completed"
                _buyEnabled.value = true
                _consumeEnabled.value = false
            }
        }
    }

    // restaurando compras apos fechar o aplicativo ou reinicialo,
    // com as funcoes acima podemos fazer compras no app mas se por um acaso nao finalizarmos a compra
    // e fecharmos o app a compra sera perdida, para isso precisamos criar um metodo que guarde as informacoes
    private fun reloadPurchase() {
        val queryPurchaseParams = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.INAPP)
            .build()
        billingClient.queryPurchasesAsync(
            queryPurchaseParams,
            purchasesListener
        )
    }

    private val purchasesListener = PurchasesResponseListener { billingResult, purchases ->
        if (purchases.isNotEmpty()) {
            purchase = purchases.first()
            _buyEnabled.value = false
            _consumeEnabled.value = true
            _statusText.value = "Previous Purchase Found"
        } else {
            _buyEnabled.value = true
            _consumeEnabled.value = false
        }
         
    }

}
