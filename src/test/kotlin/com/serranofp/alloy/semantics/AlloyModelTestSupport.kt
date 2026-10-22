package com.serranofp.alloy.semantics

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.psi.PsiFile
import com.serranofp.alloy.psi.AlloyFile
import edu.mit.csail.sdg.parser.CompUtil
import java.util.concurrent.TimeUnit

/**
 * The model of [file], computed as highlighting computes it: in a read action, off the EDT. Afterwards
 * resolution on the EDT can use it as the cached model. What the computation throws is thrown here.
 */
fun computeModel(file: PsiFile): AlloyModelResult =
    ApplicationManager.getApplication()
        .executeOnPooledThread<Result<AlloyModelResult>> {
            runCatching {
                ReadAction.computeBlocking<AlloyModelResult, Throwable> {
                    AlloyModelService.getInstance(file.project).model(file as AlloyFile)
                }
            }
        }
        .get(30, TimeUnit.SECONDS)
        .getOrThrow()

/** Puts back the analyzer's own parse, after a test replaced [AlloyModelService.parse]. */
fun AlloyModelService.restoreParse() {
    parse = CompUtil::parseEverything_fromFile
}
