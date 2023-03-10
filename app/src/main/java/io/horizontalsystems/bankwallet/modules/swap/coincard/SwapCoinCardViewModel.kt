package io.horizontalsystems.bankwallet.modules.swap.coincard

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import io.horizontalsystems.bankwallet.R
import io.horizontalsystems.bankwallet.core.fiat.AmountTypeSwitchService
import io.horizontalsystems.bankwallet.core.fiat.AmountTypeSwitchService.AmountType
import io.horizontalsystems.bankwallet.core.fiat.FiatService
import io.horizontalsystems.bankwallet.core.providers.Translator
import io.horizontalsystems.bankwallet.entities.CoinValue
import io.horizontalsystems.bankwallet.entities.CurrencyValue
import io.horizontalsystems.bankwallet.modules.send.SendModule.AmountInfo
import io.horizontalsystems.bankwallet.modules.swap.SwapMainModule
import io.horizontalsystems.bankwallet.modules.swap.SwapViewItemHelper
import io.horizontalsystems.marketkit.models.Token
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.schedulers.Schedulers
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.*

class SwapCoinCardViewModel(
    private val coinCardService: ISwapCoinCardService,
    private val fiatService: FiatService,
    private val switchService: AmountTypeSwitchService,
    private val formatter: SwapViewItemHelper,
    private val resetAmountOnCoinSelect: Boolean,
    val dex: SwapMainModule.Dex
) : ViewModel() {

    private val disposables = CompositeDisposable()

    private val validDecimals: Int
        get() {
            val decimals = when (switchService.amountType) {
                AmountType.Coin -> coinCardService.token?.decimals ?: maxValidDecimals
                AmountType.Currency -> fiatService.currency.decimal
            }
            return decimals
        }

    private val uuidString: String
        get() = UUID.randomUUID().leastSignificantBits.toString()

    private val amountLiveData = MutableLiveData<Triple<String?, String?, String?>>(Triple(null, null, null))
    private val balanceLiveData = MutableLiveData<String?>(null)
    private val balanceErrorLiveData = MutableLiveData(false)
    private val tokenCodeLiveData = MutableLiveData<Token?>()
    private val isEstimatedLiveData = MutableLiveData(false)
    private val secondaryInfoLiveData = MutableLiveData<String?>(null)
    private val hasNonZeroBalanceLiveData = MutableLiveData<Boolean?>(null)

    //region outputs
    fun amountLiveData(): LiveData<Triple<String?, String?, String?>> = amountLiveData
    fun balanceLiveData(): LiveData<String?> = balanceLiveData
    fun balanceErrorLiveData(): LiveData<Boolean> = balanceErrorLiveData
    fun tokenCodeLiveData(): LiveData<Token?> = tokenCodeLiveData
    fun isEstimatedLiveData(): LiveData<Boolean> = isEstimatedLiveData
    fun secondaryInfoLiveData(): LiveData<String?> = secondaryInfoLiveData
    fun hasNonZeroBalance(): LiveData<Boolean?> = hasNonZeroBalanceLiveData

    private val prefix = if (switchService.amountType == AmountType.Currency) fiatService.currency.symbol else null
    var inputParams by mutableStateOf(
        InputParams(
            switchService.amountType, prefix, switchService.toggleAvailable
        )
    )

    fun onSelectCoin(token: Token) {
        coinCardService.onSelectCoin(token)
        fiatService.set(token)
        if (resetAmountOnCoinSelect) {
            amountLiveData.postValue(Triple(uuidString, "", null))
            onChangeAmount("")
        }
    }

    fun onChangeAmount(amount: String?) {
        val validAmount = amount?.toBigDecimalOrNull()
        val fullAmountInfo = fiatService.buildAmountInfo(validAmount)

        syncFullAmountInfo(fullAmountInfo, true, validAmount)
    }

    fun onSetAmountInBalancePercent(percent: Int) {
        val coinDecimals = coinCardService.token?.decimals ?: maxValidDecimals
        val percentRatio = BigDecimal.valueOf(percent.toDouble() / 100)
        val coinAmount = coinCardService.balance?.multiply(percentRatio)?.setScale(coinDecimals, RoundingMode.FLOOR) ?: return

        val fullAmountInfo = fiatService.buildForCoin(coinAmount)
        syncFullAmountInfo(fullAmountInfo, true, coinAmount)
    }

    fun isValid(amount: String?): Boolean {
        val newAmount = amount?.toBigDecimalOrNull()

        return when {
            amount.isNullOrBlank() -> true
            newAmount != null && newAmount.scale() > validDecimals -> false
            else -> true
        }
    }

    fun onSwitch() {
        switchService.toggle()
    }

    //endregion

    init {
        subscribeToServices()
    }

    private fun subscribeToServices() {
        syncEstimated()
        syncCoin(coinCardService.token)
        syncAmount(coinCardService.amount, true)
        syncBalance(coinCardService.balance)

        coinCardService.isEstimatedObservable
            .subscribeOn(Schedulers.io())
            .subscribe { syncEstimated() }
            .let { disposables.add(it) }

        coinCardService.amountObservable
            .subscribeOn(Schedulers.io())
            .subscribe { syncAmount(it.orElse(null)) }
            .let { disposables.add(it) }

        coinCardService.tokenObservable
            .subscribeOn(Schedulers.io())
            .subscribe { syncCoin(it.orElse(null)) }
            .let { disposables.add(it) }

        coinCardService.balanceObservable
            .subscribeOn(Schedulers.io())
            .subscribe { syncBalance(it.orElse(null)) }
            .let { disposables.add(it) }

        coinCardService.errorObservable
            .subscribeOn(Schedulers.io())
            .subscribe { syncError(it.orElse(null)) }
            .let { disposables.add(it) }

        fiatService.fullAmountInfoObservable
            .subscribeOn(Schedulers.io())
            .subscribe { syncFullAmountInfo(it.orElse(null), false) }
            .let { disposables.add(it) }

        switchService.toggleAvailableObservable
            .subscribeOn(Schedulers.io())
            .subscribe { updateInputFields() }
            .let { disposables.add(it) }
    }

    private fun syncEstimated() {
        isEstimatedLiveData.postValue(coinCardService.isEstimated)
    }

    private fun syncAmount(amount: BigDecimal?, force: Boolean = false) {
        if (coinCardService.isEstimated || force) {
            val fullAmountInfo = fiatService.buildForCoin(amount)
            syncFullAmountInfo(fullAmountInfo, false)
        }
    }

    private fun syncCoin(token: Token?) {
        fiatService.set(token)
        tokenCodeLiveData.postValue(token)
    }

    private fun syncBalance(balance: BigDecimal?) {
        val token = coinCardService.token
        val formattedBalance: String?
        val hasNonZeroBalance: Boolean?
        when {
            token == null -> {
                formattedBalance = Translator.getString(R.string.NotAvailable)
                hasNonZeroBalance = null
            }
            balance == null -> {
                formattedBalance = null
                hasNonZeroBalance = null
            }
            else -> {
                formattedBalance = formatter.coinAmount(balance, token.coin.code)
                hasNonZeroBalance = balance > BigDecimal.ZERO
            }
        }
        balanceLiveData.postValue(formattedBalance)
        hasNonZeroBalanceLiveData.postValue(hasNonZeroBalance)
    }

    private fun syncError(error: Throwable?) {
        balanceErrorLiveData.postValue(error != null)
    }

    private fun secondaryInfoPlaceHolder(): String? = when (switchService.amountType) {
        AmountType.Coin -> {
            val amountInfo = AmountInfo.CurrencyValueInfo(CurrencyValue(fiatService.currency, BigDecimal.ZERO))
            amountInfo.getFormatted()
        }
        AmountType.Currency -> {
            val amountInfo = coinCardService.token?.let {
                AmountInfo.CoinValueInfo(
                    CoinValue(it, BigDecimal.ZERO)
                )
            }
            amountInfo?.getFormatted()
        }
    }

    private fun syncFullAmountInfo(
        fullAmountInfo: FiatService.FullAmountInfo?,
        force: Boolean = false,
        inputAmount: BigDecimal? = null
    ) {
        updateInputFields()

        if (fullAmountInfo == null) {
            amountLiveData.postValue(Triple(uuidString, null, null))
            secondaryInfoLiveData.postValue(secondaryInfoPlaceHolder())

            setCoinValueToService(inputAmount, force)
        } else {
            val decimals = fullAmountInfo.primaryDecimal
            val amountString = fullAmountInfo.primaryValue.setScale(decimals, RoundingMode.FLOOR)?.stripTrailingZeros()?.toPlainString()

            val primaryAmountPrefix = if (fullAmountInfo.primaryInfo is AmountInfo.CurrencyValueInfo)
                fullAmountInfo.primaryInfo.currencyValue.currency.symbol
            else
                null

            amountLiveData.postValue(Triple(uuidString, amountString, primaryAmountPrefix))
            secondaryInfoLiveData.postValue(fullAmountInfo.secondaryInfo?.getFormatted())

            setCoinValueToService(fullAmountInfo.coinValue.value, force)
        }
        syncEstimated()
    }

    private fun updateInputFields() {
        val switchAvailable = switchService.toggleAvailable
        val prefix = if (switchService.amountType == AmountType.Currency) fiatService.currency.symbol else null
        val inputParams = InputParams(switchService.amountType, prefix, switchAvailable)
        this.inputParams = inputParams
    }

    private fun setCoinValueToService(coinAmount: BigDecimal?, force: Boolean) {
        if (force || !coinCardService.isEstimated) {
            coinCardService.onChangeAmount(coinAmount)
        }
    }

    override fun onCleared() {
        disposables.clear()
    }

    companion object {
        private const val maxValidDecimals = 8
    }
}

class InputParams(
    val amountType: AmountType,
    val primaryPrefix: String?,
    val switchEnabled: Boolean
)
