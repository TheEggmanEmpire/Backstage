package com.eggman.backstage.ui.report

import androidx.compose.runtime.Composable

@Suppress("UNUSED_PARAMETER")
@Composable
internal fun DeveloperReportButton(
    report: AiContentReport,
    busy: Boolean,
    setBusy: (Boolean) -> Unit,
    setError: (String?) -> Unit,
) = Unit
