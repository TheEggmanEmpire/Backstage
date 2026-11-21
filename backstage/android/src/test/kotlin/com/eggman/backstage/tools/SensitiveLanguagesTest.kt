package com.eggman.backstage.tools
import org.junit.Assert.*
import org.junit.Test

class SensitiveLanguagesTest {
    @Test fun paymentLabelsAcrossLanguages() {
        listOf("betalen", "kopen", "bestellen", "nu kopen", "comprar agora", "finalizar compra", "pagar", "立即购买", "提交订单", "付款", "今すぐ購入する", "支払う", "注文を確定", "결제하기", "주문하기", "भुगतान करें", "अभी खरीदें").forEach {
            assertEquals(it, "payment", SensitiveActionGuard.classify(it))
        }
    }
    @Test fun accountDeletionAcrossLanguages() {
        listOf("account verwijderen", "excluir conta", "删除账户", "アカウント削除", "계정 삭제", "खाता हटाएं").forEach {
            assertEquals(it, "account", SensitiveActionGuard.classify(it))
        }
    }
    @Test fun latinMatchesStillUseWordBoundaries() {
        listOf("payerboard", "kopend", "repagare").forEach { assertNull(it, SensitiveActionGuard.classify(it)) }
    }
    @Test fun sendAndDeleteAcrossLanguages() {
        listOf("送信", "发送", "전송", "보내기", "भेजें", "versturen", "verzenden", "enviar").forEach {
            assertEquals(it, "send", SensitiveActionGuard.classify(it))
        }
        listOf("削除", "删除", "삭제", "हटाएं", "Excluir", "apagar", "verwijderen").forEach {
            assertEquals(it, "delete", SensitiveActionGuard.classify(it))
        }
        listOf("ligar", "bellen").forEach { assertEquals(it, "call", SensitiveActionGuard.classify(it)) }
    }
    @Test fun navigationAndChatRowsAreNotActionButtons() {
        listOf("支付方式", "결제 내역", "購入履歴", "購入方法", "결제 방법", "支付历史", "我已经付款了", "付款记录", "支付设置", "支払い設定", "购买的订单已经到了").forEach {
            assertNull(it, SensitiveActionGuard.classify(it))
        }
    }

}
