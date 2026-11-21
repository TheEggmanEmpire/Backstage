package com.eggman.backstage.tools
import org.junit.Assert.*
import org.junit.Test
class SensitiveActionClassificationTest {
    @Test fun knownImperativesRemainGuardedWithSuffixes() {
        listOf("注文を確定する", "注文を確定します", "アカウントを削除する", "アカウントを削除します",
            "今すぐ買う", "買う", "購入する", "購入します", "결제하기", "भुगतान करें").forEach {
            assertNotNull(it, SensitiveActionGuard.classify(it))
        }
    }
    @Test fun historyWordsDoNotHideDestructiveActions() {
        listOf("履歴を削除", "删除聊天记录", "내역 삭제", "记录删除", "送信履歴を削除").forEach {
            assertNotNull(it, SensitiveActionGuard.classify(it))
        }
    }
    @Test fun paymentNavigationAndNarrationStillPass() {
        listOf("支付方式", "결제 내역", "我已经付款了", "購入履歴", "購入方法").forEach {
            assertNull(it, SensitiveActionGuard.classify(it))
        }
    }
}
