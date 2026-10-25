"use strict";

const crypto = require("node:crypto");

const {
  pushConfigured,
  validDeviceToken,
  sendOrderStatus
} = require("./firebase-push");

const ORDER_PATTERN =
  /^LV-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;

const SECRET_PATTERN = /^[0-9a-f]{64}$/;

const VALID_STATUSES = new Set([
  "new",
  "confirmed",
  "preparing",
  "delivering",
  "ready",
  "completed",
  "cancelled"
]);

const MAX_DEVICES_PER_ORDER = 3;
const MAX_ATTEMPTS = 8;

function fail(status, message) {
  const error = new Error(message);
  error.status = status;
  return error;
}

function hash(value) {
  return crypto
    .createHash("sha256")
    .update(value)
    .digest("hex");
}

function equalHashes(first, second) {
  if (
    typeof first !== "string" ||
    typeof second !== "string" ||
    !SECRET_PATTERN.test(first) ||
    !SECRET_PATTERN.test(second)
  ) {
    return false;
  }

  return crypto.timingSafeEqual(
    Buffer.from(first, "hex"),
    Buffer.from(second, "hex")
  );
}

// -------------------- ТАБЛИЦЫ --------------------

async function initializeOrderPush(pool) {
  const client = await pool.connect();

  try {
    await client.query("BEGIN");

    await client.query(
      "SELECT pg_advisory_xact_lock(781240194)"
    );

    await client.query(`
      CREATE TABLE IF NOT EXISTS lv_push_subscriptions (
        order_id TEXT NOT NULL
          REFERENCES lv_orders(id) ON DELETE CASCADE,

        token_hash TEXT NOT NULL,
        device_token TEXT NOT NULL,

        created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
        updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

        PRIMARY KEY (order_id, token_hash)
      )
    `);

    await client.query(`
      CREATE TABLE IF NOT EXISTS lv_push_queue (
        id TEXT PRIMARY KEY,

        order_id TEXT NOT NULL,
        token_hash TEXT NOT NULL,
        order_status TEXT NOT NULL,

        state TEXT NOT NULL DEFAULT 'pending',
        attempts INTEGER NOT NULL DEFAULT 0,

        next_attempt_at TIMESTAMPTZ
          NOT NULL DEFAULT NOW(),

        locked_until TIMESTAMPTZ,
        claim_id TEXT,

        last_error TEXT NOT NULL DEFAULT '',

        created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
        updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

        FOREIGN KEY (order_id, token_hash)
          REFERENCES lv_push_subscriptions(
            order_id, token_hash
          )
          ON DELETE CASCADE
      )
    `);

    await client.query(`
      CREATE INDEX IF NOT EXISTS lv_push_queue_pending_idx
      ON lv_push_queue (
        state, next_attempt_at, created_at
      )
    `);

    await client.query("COMMIT");
  } catch (error) {
    await client.query("ROLLBACK").catch(() => {});
    throw error;
  } finally {
    client.release();
  }
}

// -------------------- ПРОВЕРКА ДОСТУПА --------------------

function validateSubscriptionInput(data) {
  if (
    !data ||
    typeof data !== "object" ||
    typeof data.id !== "string" ||
    !ORDER_PATTERN.test(data.id) ||
    typeof data.orderAccessToken !== "string" ||
    !SECRET_PATTERN.test(data.orderAccessToken)
  ) {
    throw fail(
      404,
      "Не удалось подтвердить доступ к заказу."
    );
  }

  if (!validDeviceToken(data.fcmToken)) {
    throw fail(
      400,
      "Некорректный адрес устройства для уведомлений."
    );
  }
}

async function authorizedOrder(client, data) {
  const result = await client.query(
    `SELECT *
     FROM lv_orders
     WHERE id = $1
     FOR UPDATE`,
    [data.id]
  );

  const row = result.rows[0];

  const expected = row && row.tracking_token_hash
    ? row.tracking_token_hash
    : "0".repeat(64);

  const matches = equalHashes(
    hash(data.orderAccessToken),
    expected
  );

  if (
    !row ||
    !row.tracking_token_hash ||
    !matches
  ) {
    throw fail(
      404,
      "Не удалось подтвердить доступ к заказу."
    );
  }

  return row;
}

// -------------------- ПРИВЯЗКА УСТРОЙСТВА --------------------

async function subscribeOrderPush(pool, data) {
  validateSubscriptionInput(data);

  const tokenHash = hash(data.fcmToken);
  const client = await pool.connect();

  try {
    await client.query("BEGIN");

    /*
     * Блокировка заказа согласована с изменением статуса.
     * Подписка и смена статуса не потеряют событие
     * из-за одновременного выполнения.
     */
    const order = await authorizedOrder(client, data);

    const existing = await client.query(
      `SELECT 1
       FROM lv_push_subscriptions
       WHERE order_id = $1 AND token_hash = $2`,
      [order.id, tokenHash]
    );

    if (existing.rows.length) {
      await client.query(
        `UPDATE lv_push_subscriptions
         SET updated_at = NOW()
         WHERE order_id = $1 AND token_hash = $2`,
        [order.id, tokenHash]
      );

      await client.query("COMMIT");

      return {
        subscribed: true,
        newlySubscribed: false
      };
    }

    const count = await client.query(
      `SELECT COUNT(*)::integer AS total
       FROM lv_push_subscriptions
       WHERE order_id = $1`,
      [order.id]
    );

    if (count.rows[0].total >= MAX_DEVICES_PER_ORDER) {
      throw fail(
        409,
        "Для этого заказа уже подключено " +
        "максимальное количество устройств."
      );
    }

    await client.query(
      `INSERT INTO lv_push_subscriptions (
         order_id, token_hash, device_token
       )
       VALUES ($1, $2, $3)`,
      [order.id, tokenHash, data.fcmToken]
    );

    /*
     * Новое устройство получает текущий статус.
     * Повторная регистрация того же адреса
     * повторного уведомления не создаёт.
     */
    if (VALID_STATUSES.has(order.status)) {
      await client.query(
        `INSERT INTO lv_push_queue (
           id, order_id, token_hash, order_status
         )
         VALUES ($1, $2, $3, $4)`,
        [
          crypto.randomUUID(),
          order.id,
          tokenHash,
          order.status
        ]
      );
    }

    await client.query("COMMIT");

    return {
      subscribed: true,
      newlySubscribed: true
    };
  } catch (error) {
    await client.query("ROLLBACK").catch(() => {});
    throw error;
  } finally {
    client.release();
  }
}

async function unsubscribeOrderPush(pool, data) {
  validateSubscriptionInput(data);

  const client = await pool.connect();

  try {
    await client.query("BEGIN");

    await authorizedOrder(client, data);

    await client.query(
      `DELETE FROM lv_push_subscriptions
       WHERE order_id = $1 AND token_hash = $2`,
      [data.id, hash(data.fcmToken)]
    );

    await client.query("COMMIT");

    return {
      subscribed: false
    };
  } catch (error) {
    await client.query("ROLLBACK").catch(() => {});
    throw error;
  } finally {
    client.release();
  }
}

// -------------------- СМЕНА СТАТУСА --------------------

async function changeOrderStatusWithPush(
  pool,
  id,
  status
) {
  if (
    typeof id !== "string" ||
    !ORDER_PATTERN.test(id)
  ) {
    throw fail(400, "Некорректный номер заказа.");
  }

  if (!VALID_STATUSES.has(status)) {
    throw fail(400, "Некорректный статус заказа.");
  }

  const client = await pool.connect();

  try {
    await client.query("BEGIN");

    const found = await client.query(
      `SELECT *
       FROM lv_orders
       WHERE id = $1
       FOR UPDATE`,
      [id]
    );

    const previous = found.rows[0];

    if (!previous) {
      throw fail(404, "Заказ не найден.");
    }

    /*
     * Повторное сохранение того же статуса
     * не порождает новое уведомление.
     */
    if (previous.status === status) {
      await client.query("COMMIT");
      return previous;
    }

    const updated = await client.query(
      `UPDATE lv_orders
       SET status = $2, updated_at = NOW()
       WHERE id = $1
       RETURNING *`,
      [id, status]
    );

    const subscriptions = await client.query(
      `SELECT token_hash
       FROM lv_push_subscriptions
       WHERE order_id = $1`,
      [id]
    );

    for (const subscription of subscriptions.rows) {
      await client.query(
        `INSERT INTO lv_push_queue (
           id, order_id, token_hash, order_status
         )
         VALUES ($1, $2, $3, $4)`,
        [
          crypto.randomUUID(),
          id,
          subscription.token_hash,
          status
        ]
      );
    }

    /*
     * Статус и задания на уведомление сохраняются
     * в одной транзакции.
     *
     * Обращение к Firebase происходит позже,
     * без удержания транзакции заказа.
     */
    await client.query("COMMIT");

    return updated.rows[0];
  } catch (error) {
    await client.query("ROLLBACK").catch(() => {});
    throw error;
  } finally {
    client.release();
  }
}

// -------------------- ОЧЕРЕДЬ --------------------

async function claimNextJob(pool) {
  const claimId = crypto.randomUUID();

  const result = await pool.query(
    `WITH candidate AS (
       SELECT id
       FROM lv_push_queue
       WHERE (
         state = 'pending'
         AND next_attempt_at <= NOW()
       )
       OR (
         state = 'sending'
         AND locked_until < NOW()
       )
       ORDER BY created_at, id
       FOR UPDATE SKIP LOCKED
       LIMIT 1
     )
     UPDATE lv_push_queue AS queue
     SET
       state = 'sending',
       claim_id = $1,
       locked_until = NOW() + INTERVAL '2 minutes',
       attempts = attempts + 1,
       updated_at = NOW()
     FROM candidate
     WHERE queue.id = candidate.id
     RETURNING queue.*`,
    [claimId]
  );

  return result.rows[0] || null;
}

async function finishJob(pool, job, state, code = "") {
  await pool.query(
    `UPDATE lv_push_queue
     SET
       state = $3,
       last_error = $4,
       claim_id = NULL,
       locked_until = NULL,
       updated_at = NOW()
     WHERE id = $1 AND claim_id = $2`,
    [job.id, job.claim_id, state, code]
  );
}

async function postponeJob(pool, job, code) {
  const delaySeconds =
    Math.min(
      3600,
      30 * Math.pow(2, Math.max(0, job.attempts - 1))
    ) + crypto.randomInt(0, 16);

  await pool.query(
    `UPDATE lv_push_queue
     SET
       state = 'pending',
       last_error = $3,
       next_attempt_at =
         NOW() + ($4::integer * INTERVAL '1 second'),
       claim_id = NULL,
       locked_until = NULL,
       updated_at = NOW()
     WHERE id = $1 AND claim_id = $2`,
    [
      job.id,
      job.claim_id,
      code,
      delaySeconds
    ]
  );
}

async function processJob(pool, job) {
  if (job.attempts > MAX_ATTEMPTS) {
    await finishJob(
      pool,
      job,
      "failed",
      "PUSH_ATTEMPTS_EXHAUSTED"
    );

    return;
  }

  const found = await pool.query(
    `SELECT
       subscriptions.device_token,
       orders.order_number,
       orders.status AS current_status,
       orders.updated_at AS status_updated_at
     FROM lv_push_subscriptions AS subscriptions
     JOIN lv_orders AS orders
       ON orders.id = subscriptions.order_id
     WHERE subscriptions.order_id = $1
       AND subscriptions.token_hash = $2`,
    [job.order_id, job.token_hash]
  );

  const target = found.rows[0];

  if (!target) {
    await finishJob(pool, job, "skipped");
    return;
  }

  /*
   * Не отправляем очевидно устаревший статус,
   * если в очереди задержалось старое сообщение.
   *
   * Полностью исключить изменение статуса
   * непосредственно во время сетевой отправки
   * невозможно: актуальный статус берётся из API.
   */
  if (target.current_status !== job.order_status) {
    await finishJob(
      pool,
      job,
      "skipped",
      "PUSH_STATUS_SUPERSEDED"
    );

    return;
  }

  try {
    const result = await sendOrderStatus({
      deviceToken: target.device_token,
      orderId: job.order_id,
      orderNumber: target.order_number,
      status: job.order_status
    });

    if (result.invalidToken) {
      /*
       * Удаляем недействующий адрес устройства.
       * Связанные задания удаляются внешним ключом.
       */
      await pool.query(
        `DELETE FROM lv_push_subscriptions
         WHERE token_hash = $1`,
        [job.token_hash]
      );

      return;
    }

    await finishJob(pool, job, "sent");
  } catch (error) {
    const code =
      typeof error.code === "string" &&
      /^PUSH_[A-Z0-9_]+$/.test(error.code)
        ? error.code
        : "PUSH_UNKNOWN_ERROR";

    if (
      error.retryable &&
      job.attempts < MAX_ATTEMPTS
    ) {
      await postponeJob(pool, job, code);
    } else {
      await finishJob(pool, job, "failed", code);
    }

    /*
     * Не пишем в журнал:
     * - адрес устройства;
     * - ключ просмотра заказа;
     * - ключ Firebase;
     * - персональные данные покупателя.
     */
    console.error(
      "Ошибка push-уведомления. Код:",
      code
    );
  }
}

// -------------------- ФОНОВЫЙ ОБРАБОТЧИК --------------------

function startOrderPushWorker(pool) {
  let stopped = false;
  let timer = null;
  let running = null;
  let lastCleanup = 0;

  async function batch() {
    if (!pushConfigured()) {
      return;
    }

    if (Date.now() - lastCleanup > 3600000) {
      await pool.query(
        `DELETE FROM lv_push_queue
         WHERE state IN ('sent', 'skipped', 'failed')
           AND updated_at <
             NOW() - INTERVAL '7 days'`
      );

      lastCleanup = Date.now();
    }

    for (let index = 0; index < 10; index += 1) {
      if (stopped) {
        return;
      }

      const job = await claimNextJob(pool);

      if (!job) {
        return;
      }

      await processJob(pool, job);
    }
  }

  function schedule(delay) {
    if (stopped) {
      return;
    }

    timer = setTimeout(tick, delay);
    timer.unref();
  }

  async function tick() {
    if (stopped) {
      return;
    }

    running = batch();

    try {
      await running;
    } catch {
      console.error(
        "Не удалось обработать очередь push. " +
        "Проверка будет повторена."
      );
    } finally {
      running = null;
      schedule(5000);
    }
  }

  schedule(1000);

  return {
    async stop() {
      stopped = true;

      if (timer) {
        clearTimeout(timer);
        timer = null;
      }

      if (running) {
        await running.catch(() => {});
      }
    }
  };
}

module.exports = {
  initializeOrderPush,
  subscribeOrderPush,
  unsubscribeOrderPush,
  changeOrderStatusWithPush,
  startOrderPushWorker
};

// КОНЕЦ ФАЙЛА
