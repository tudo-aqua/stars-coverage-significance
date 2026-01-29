/*
 * Copyright 2026 The STARS Coverage Significance Authors
 * SPDX-License-Identifier: Apache-2.0
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package tools.aqua.stars.coverage.significance.db

import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction

/**
 * Executes the given [block] within a database transaction. If a transaction is already active, it
 * reuses that transaction; otherwise, it starts a new one.
 *
 * @param T The return type of the block.
 * @param block The block of code to execute within the transaction.
 * @return The result of the block execution.
 */
inline fun <T> db(crossinline block: () -> T): T =
    if (TransactionManager.currentOrNull() != null) {
      block()
    } else {
      transaction { block() }
    }
