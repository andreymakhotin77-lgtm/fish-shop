"use strict";

const http = require("node:http");
const crypto = require("node:crypto");
const fs = require("node:fs/promises");
const path = require("node:path");
const { Pool } = require("pg");

// -------------------- НАСТРОЙКИ --------------------

const PORT = Number(process.env.PORT || 3000);

const BOT_TOKEN = process.env.TELEGRAM_BOT_TOKEN || "";
const CHAT_ID = process.env.TELEGRAM_CHAT_ID || "";
const ADMIN_TOKEN = process.env.ADMIN_TOKEN || "";

const CLOUD_NAME =
  (process.env.CLOUDINARY_CLOUD_NAME || "").trim();

const CLOUD_API_KEY =
  (process.env.CLOUDINARY_API_KEY || "").trim();

const CLOUD_API_SECRET =
  (process.env.CLOUDINARY_API_SECRET || "").trim();

const PICKUP =
  "г. Макеевка, ул. Плеханова, 1в, магазин «Лавка Вкуса»";

const MAX_IMAGE_BYTES = 5 * 1024 * 1024;
const MAX_UPLOAD_BODY_BYTES = 7 * 1024 * 1024 + 16384;

const UUID_PATTERN =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;

const ORDER_ID_PATTERN =
  /^LV-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;

const TOKEN_PATTERN = /^[0-9a-f]{64}$/;
const PRODUCT_ID_PATTERN = /^[a-z0-9_-]{1,80}$/;

const STATUS_NAMES = {
  new: "Новый",
  confirmed: "Подтверждён",
  preparing: "Собирается",
  delivering: "В доставке",
  ready: "Готов к самовывозу",
  completed: "Выполнен",
  cancelled: "Отменён"
};

const ORDER_STATUSES = Object.keys(STATUS_NAMES);

if (!process.env.DATABASE_URL) {
  console.error("Не задана переменная DATABASE_URL.");
  process.exit(1);
}

const pool = new Pool({
  connectionString: process.env.DATABASE_URL,
  max: 5,
  connectionTimeoutMillis: 15000,
  idleTimeoutMillis: 30000
});

pool.on("error", () => {
  console.error("Ошибка фонового подключения к базе данных.");
});

// -------------------- ОБЩИЕ ФУНКЦИИ --------------------

function fail(status, message) {
  const error = new Error(message);
  error.status = status;
  return error;
}

function json(res, status, data) {
  if (res.destroyed || res.writableEnded) return;

  res.writeHead(status, {
    "Content-Type": "application/json; charset=utf-8",
    "Cache-Control": "no-store",
    "X-Content-Type-Options": "nosniff",
    "Referrer-Policy": "no-referrer"
  });

  res.end(JSON.stringify(data));
}

function text(value, max) {
  return typeof value === "string"
    ? value.trim().slice(0, max)
    : "";
}

function sha256(value) {
  return crypto
    .createHash("sha256")
    .update(value)
    .digest("hex");
}

function sameHash(first, second) {
  if (
    typeof first !== "string" ||
    typeof second !== "string" ||
    !TOKEN_PATTERN.test(first) ||
    !TOKEN_PATTERN.test(second)
  ) {
    return false;
  }

  return crypto.timingSafeEqual(
    Buffer.from(first, "hex"),
    Buffer.from(second, "hex")
  );
}

function isKilogram(unit) {
  return String(unit || "").trim().toLowerCase() === "кг";
}

const moneyFormatter = new Intl.NumberFormat("ru-RU", {
  minimumFractionDigits: 0,
  maximumFractionDigits: 2
});

function rub(value) {
  return moneyFormatter.format(Number(value)) + " ₽";
}

function cloudinaryConfigured() {
  return Boolean(
    /^[a-z0-9_-]+$/i.test(CLOUD_NAME) &&
    CLOUD_API_KEY &&
    CLOUD_API_SECRET
  );
}

function checkAdmin(req) {
  if (ADMIN_TOKEN.length < 32) {
    throw fail(503, "Доступ продавца ещё не настроен.");
  }

  const received = String(req.headers.authorization || "");
  const expected = `Bearer ${ADMIN_TOKEN}`;

  if (!sameHash(sha256(received), sha256(expected))) {
    throw fail(401, "Неверный ключ доступа продавца.");
  }
}

async function readJson(req, maxBytes = 32768) {
  const contentType = String(
    req.headers["content-type"] || ""
  ).toLowerCase();

  if (
    contentType.split(";")[0].trim() !== "application/json"
  ) {
    req.resume();
    throw fail(415, "Ожидается JSON.");
  }

  const declaredLength = Number(
    req.headers["content-length"] || 0
  );

  if (
    Number.isFinite(declaredLength) &&
    declaredLength > maxBytes
  ) {
    req.resume();
    throw fail(413, "Слишком большой запрос.");
  }

  const buffer = await new Promise((resolve, reject) => {
    let chunks = [];
    let size = 0;
    let finished = false;

    function rejectOnce(error) {
      if (finished) return;
      finished = true;
      chunks = [];
      reject(error);
    }

    req.on("data", (chunk) => {
      if (finished) return;

      size += chunk.length;

      if (size > maxBytes) {
        rejectOnce(fail(413, "Слишком большой запрос."));
        return;
      }

      chunks.push(chunk);
    });

    req.on("end", () => {
      if (finished) return;
      finished = true;
      const result = Buffer.concat(chunks);
      chunks = [];
      resolve(result);
    });

    req.on("aborted", () => {
      rejectOnce(fail(400, "Загрузка запроса прервана."));
    });

    req.on("error", () => {
      rejectOnce(fail(400, "Не удалось прочитать запрос."));
    });
  });

  try {
    const data = JSON.parse(buffer.toString("utf8"));

    if (
      !data ||
      typeof data !== "object" ||
      Array.isArray(data)
    ) {
      throw new Error();
    }

    return data;
  } catch {
    throw fail(400, "Некорректный JSON.");
  }
}

// -------------------- ОГРАНИЧЕНИЯ ЗАПРОСОВ --------------------

// Лимиты действуют в пределах одного запущенного сервера.
let writesStarted = Date.now();
let writeCount = 0;

function limitWrites(req) {
  if (
    !["POST", "PUT", "PATCH", "DELETE"].includes(req.method)
  ) {
    return;
  }

  if (Date.now() - writesStarted >= 60000) {
    writesStarted = Date.now();
    writeCount = 0;
  }

  writeCount += 1;

  if (writeCount > 120) {
    throw fail(
      429,
      "Слишком много запросов. Попробуйте через минуту."
    );
  }
}

let uploadsStarted = Date.now();
let uploadCount = 0;
let activeUploads = 0;

function limitUploads() {
  if (Date.now() - uploadsStarted >= 60000) {
    uploadsStarted = Date.now();
    uploadCount = 0;
  }

  uploadCount += 1;

  if (uploadCount > 10) {
    throw fail(
      429,
      "Можно загрузить до 10 фотографий за минуту."
    );
  }

  if (activeUploads >= 2) {
    throw fail(
      503,
      "Другие фотографии ещё загружаются. Подождите немного."
    );
  }
}

// -------------------- ПРЕДСТАВЛЕНИЕ ДАННЫХ --------------------

function productView(row) {
  const priceRub = Number(row.price_rub);

  const candidate =
    row.old_price_rub === null ||
    row.old_price_rub === undefined
      ? null
      : Number(row.old_price_rub);

  const isPromotion =
    Number.isInteger(candidate) && candidate > priceRub;

  const oldPriceRub = isPromotion ? candidate : null;

  const discountPercent = isPromotion
    ? Math.floor(
        ((oldPriceRub - priceRub) * 100) / oldPriceRub
      )
    : 0;

  return {
    id: row.id,
    name: row.name,
    category: row.category,
    description: row.description,
    priceRub,
    price: priceRub,
    oldPriceRub,
    isPromotion,
    discountPercent,
    unit: row.unit,
    pack: `1 ${row.unit}`,
    imageUrl: row.image_url,
    available: row.available,
    sortOrder: row.sort_order,
    quantityStep: isKilogram(row.unit) ? 0.5 : 1,
    minQuantity: isKilogram(row.unit) ? 0.5 : 1,
    maxQuantity: 50
  };
}

function orderView(row) {
  return {
    id: row.id,
    orderNumber: Number(row.order_number),
    requestId: row.request_id,
    createdAt: row.created_at,
    updatedAt: row.updated_at,
    name: row.customer_name,
    phone: row.phone,
    fulfillment: row.fulfillment,
    address: row.address,
    comment: row.comment,
    items: row.items,
    totalRub: Number(row.total_rub),
    status: row.status,
    telegramStatus: row.telegram_status
  };
}

function customerOrderView(row) {
  return {
    id: row.id,
    orderNumber: Number(row.order_number),
    createdAt: row.created_at,
    updatedAt: row.updated_at,
    fulfillment: row.fulfillment,
    pickupAddress:
      row.fulfillment === "pickup" ? PICKUP : "",
    items: row.items,
    totalRub: Number(row.total_rub),
    status: row.status,
    statusName:
      STATUS_NAMES[row.status] || "Статус уточняется",
    payment: "on_receipt"
  };
}

// -------------------- ПОДГОТОВКА БАЗЫ --------------------

async function initializeDatabase() {
  const client = await pool.connect();

  try {
    await client.query("BEGIN");

    // Не допускаем одновременного выполнения миграций.
    await client.query(
      "SELECT pg_advisory_xact_lock(781240193)"
    );

    await client.query(`
      CREATE TABLE IF NOT EXISTS lv_products (
        id TEXT PRIMARY KEY,
        name TEXT NOT NULL,
        category TEXT NOT NULL DEFAULT 'Рыба и морепродукты',
        description TEXT NOT NULL DEFAULT '',
        price_rub INTEGER NOT NULL CHECK (price_rub > 0),
        unit TEXT NOT NULL DEFAULT 'кг',
        image_url TEXT NOT NULL DEFAULT '',
        available BOOLEAN NOT NULL DEFAULT TRUE,
        sort_order INTEGER NOT NULL DEFAULT 0,
        updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
      )
    `);

    await client.query(`
      ALTER TABLE lv_products
      ADD COLUMN IF NOT EXISTS old_price_rub INTEGER
    `);

    await client.query(`
      DO $migration$
      BEGIN
        IF NOT EXISTS (
          SELECT 1
          FROM pg_constraint
          WHERE conrelid = 'lv_products'::regclass
            AND conname = 'lv_products_old_price_check'
        ) THEN
          ALTER TABLE lv_products
          ADD CONSTRAINT lv_products_old_price_check
          CHECK (
            old_price_rub IS NULL
            OR (
              old_price_rub > price_rub
              AND old_price_rub <= 1000000
            )
          );
        END IF;
      END
      $migration$;
    `);

    await client.query(`
      CREATE TABLE IF NOT EXISTS lv_orders (
        id TEXT PRIMARY KEY,
        request_id TEXT NOT NULL UNIQUE,
        fingerprint TEXT NOT NULL,
        customer_name TEXT NOT NULL,
        phone TEXT NOT NULL,
        fulfillment TEXT NOT NULL
          CHECK (fulfillment IN ('delivery', 'pickup')),
        address TEXT NOT NULL DEFAULT '',
        comment TEXT NOT NULL DEFAULT '',
        items JSONB NOT NULL,
        total_rub NUMERIC(12,2) NOT NULL
          CHECK (total_rub > 0),
        status TEXT NOT NULL DEFAULT 'new',
        telegram_status TEXT NOT NULL DEFAULT 'pending',
        created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
        updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
      )
    `);

    const totalColumn = await client.query(`
      SELECT data_type
      FROM information_schema.columns
      WHERE table_schema = current_schema()
        AND table_name = 'lv_orders'
        AND column_name = 'total_rub'
    `);

    if (
      totalColumn.rows.length &&
      totalColumn.rows[0].data_type !== "numeric"
    ) {
      await client.query(`
        ALTER TABLE lv_orders
        ALTER COLUMN total_rub TYPE NUMERIC(12,2)
        USING total_rub::NUMERIC(12,2)
      `);
    }

    await client.query(`
      ALTER TABLE lv_orders
      ADD COLUMN IF NOT EXISTS order_number BIGINT
      GENERATED ALWAYS AS IDENTITY (START WITH 1001)
    `);

    await client.query(`
      ALTER TABLE lv_orders
      ADD COLUMN IF NOT EXISTS tracking_token_hash TEXT
    `);

    await client.query(`
      CREATE UNIQUE INDEX IF NOT EXISTS lv_orders_number_idx
      ON lv_orders (order_number)
    `);

    await client.query(`
      CREATE INDEX IF NOT EXISTS lv_orders_created_idx
      ON lv_orders (created_at DESC)
    `);

    const seeds = [
      ["crayfish", "Раки живые", "Раки", 4500, 1],
      ["mackerel", "Скумбрия без головы", "Рыба", 1400, 2],
      ["bream", "Тушка леща с икрой", "Рыба", 1700, 3]
    ];

    for (const [id, name, category, price, position] of seeds) {
      await client.query(
        `INSERT INTO lv_products
          (id, name, category, price_rub, sort_order)
         VALUES ($1, $2, $3, $4, $5)
         ON CONFLICT (id) DO NOTHING`,
        [id, name, category, price, position]
      );
    }

    await client.query("COMMIT");
  } catch (error) {
    await client.query("ROLLBACK").catch(() => {});
    throw error;
  } finally {
    client.release();
  }
}

// -------------------- ТОВАРЫ И АКЦИИ --------------------

function normalizeProduct(data, id) {
  const name = text(data.name, 120);
  const category =
    text(data.category, 80) || "Рыба и морепродукты";

  const description = text(data.description, 2000);
  const unit = text(data.unit, 20) || "кг";
  const imageUrl = text(data.imageUrl, 1500);
  const priceRub = data.priceRub;

  const available =
    data.available === undefined ? true : data.available;

  const sortOrder =
    data.sortOrder === undefined ? 0 : data.sortOrder;

  const oldPriceProvided =
    Object.prototype.hasOwnProperty.call(
      data,
      "oldPriceRub"
    );

  let oldPriceRub = null;

  if (
    oldPriceProvided &&
    data.oldPriceRub !== null &&
    data.oldPriceRub !== ""
  ) {
    oldPriceRub = data.oldPriceRub;

    if (
      !Number.isInteger(oldPriceRub) ||
      oldPriceRub < 1 ||
      oldPriceRub > 1000000
    ) {
      throw fail(
        400,
        "Старая цена должна быть целым числом " +
        "от 1 до 1000000 или оставаться пустой."
      );
    }
  }

  if (!PRODUCT_ID_PATTERN.test(id)) {
    throw fail(400, "Некорректный код товара.");
  }

  if (name.length < 2) {
    throw fail(400, "Укажите название товара.");
  }

  if (
    !Number.isInteger(priceRub) ||
    priceRub < 1 ||
    priceRub > 1000000
  ) {
    throw fail(
      400,
      "Цена должна быть целым числом от 1 до 1000000."
    );
  }

  if (
    oldPriceRub !== null &&
    oldPriceRub <= priceRub
  ) {
    throw fail(
      400,
      "Старая цена должна быть выше текущей. " +
      "Чтобы убрать акцию, очистите старую цену."
    );
  }

  if (!["кг", "шт", "уп"].includes(unit)) {
    throw fail(400, "Выберите единицу: кг, шт или уп.");
  }

  if (typeof available !== "boolean") {
    throw fail(400, "Некорректное значение наличия.");
  }

  if (
    !Number.isInteger(sortOrder) ||
    sortOrder < -100000 ||
    sortOrder > 100000
  ) {
    throw fail(400, "Некорректный порядок сортировки.");
  }

  if (imageUrl) {
    try {
      const parsed = new URL(imageUrl);

      if (
        parsed.protocol !== "https:" ||
        parsed.username ||
        parsed.password
      ) {
        throw new Error();
      }
    } catch {
      throw fail(
        400,
        "Укажите обычную HTTPS-ссылку на фотографию."
      );
    }
  }

  return {
    id,
    name,
    category,
    description,
    unit,
    imageUrl,
    priceRub,
    oldPriceRub,
    oldPriceProvided,
    available,
    sortOrder
  };
}

function mapProductDatabaseError(error) {
  if (
    error.code === "23514" &&
    error.constraint === "lv_products_old_price_check"
  ) {
    return fail(
      400,
      "Старая цена должна быть выше текущей " +
      "и не превышать 1000000 ₽. " +
      "Исправьте или очистите старую цену."
    );
  }

  return error;
}

async function createProduct(data) {
  const id =
    data.id === undefined
      ? `product-${crypto.randomUUID()}`
      : text(data.id, 80);

  const p = normalizeProduct(data, id);

  try {
    const result = await pool.query(
      `INSERT INTO lv_products (
        id, name, category, description,
        price_rub, old_price_rub, unit,
        image_url, available, sort_order
      )
      VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10)
      RETURNING *`,
      [
        p.id,
        p.name,
        p.category,
        p.description,
        p.priceRub,
        p.oldPriceRub,
        p.unit,
        p.imageUrl,
        p.available,
        p.sortOrder
      ]
    );

    return productView(result.rows[0]);
  } catch (error) {
    if (error.code === "23505") {
      throw fail(
        409,
        "Товар с таким кодом уже существует."
      );
    }

    throw mapProductDatabaseError(error);
  }
}

async function updateProduct(id, data) {
  const p = normalizeProduct(data, id);

  try {
    const result = await pool.query(
      `UPDATE lv_products SET
        name = $2,
        category = $3,
        description = $4,
        price_rub = $5,
        old_price_rub = CASE
          WHEN $6::boolean THEN $7::integer
          ELSE old_price_rub
        END,
        unit = $8,
        image_url = $9,
        available = $10,
        sort_order = $11,
        updated_at = NOW()
       WHERE id = $1
       RETURNING *`,
      [
        p.id,
        p.name,
        p.category,
        p.description,
        p.priceRub,
        p.oldPriceProvided,
        p.oldPriceRub,
        p.unit,
        p.imageUrl,
        p.available,
        p.sortOrder
      ]
    );

    if (!result.rows.length) {
      throw fail(404, "Товар не найден.");
    }

    return productView(result.rows[0]);
  } catch (error) {
    throw mapProductDatabaseError(error);
  }
}

// -------------------- ПРОВЕРКА ЗАКАЗА --------------------

function accessTokenHashFromInput(data) {
  // Совместимость с приложением без отслеживания.
  if (data.orderAccessToken === undefined) {
    return null;
  }

  if (
    typeof data.orderAccessToken !== "string" ||
    !TOKEN_PATTERN.test(data.orderAccessToken)
  ) {
    throw fail(
      400,
      "Некорректный ключ просмотра заказа. " +
      "Обновите приложение."
    );
  }

  return sha256(data.orderAccessToken);
}

function normalizeOrder(data) {
  const requestId = text(data.requestId, 80).toLowerCase();

  if (!UUID_PATTERN.test(requestId)) {
    throw fail(400, "Некорректный идентификатор заказа.");
  }

  const name = text(data.name, 80);
  const phone = text(data.phone, 32).replace(/\D/g, "");
  const fulfillment = data.fulfillment;

  const address =
    fulfillment === "delivery"
      ? text(data.address, 250)
      : "";

  const comment = text(data.comment, 300);

  if (name.length < 2) {
    throw fail(400, "Укажите имя.");
  }

  if (!/^[78]\d{10}$/.test(phone)) {
    throw fail(400, "Проверьте номер телефона.");
  }

  if (!["delivery", "pickup"].includes(fulfillment)) {
    throw fail(400, "Выберите доставку или самовывоз.");
  }

  if (fulfillment === "delivery" && address.length < 5) {
    throw fail(400, "Укажите полный адрес доставки.");
  }

  if (
    !Array.isArray(data.items) ||
    data.items.length < 1 ||
    data.items.length > 100
  ) {
    throw fail(400, "Проверьте корзину.");
  }

  const seen = new Set();

  const items = data.items.map((item) => {
    if (
      !item ||
      typeof item !== "object" ||
      Array.isArray(item)
    ) {
      throw fail(400, "Некорректная позиция заказа.");
    }

    const productId = text(item.productId, 80);
    const quantity = item.quantity;

    if (
      !PRODUCT_ID_PATTERN.test(productId) ||
      typeof quantity !== "number" ||
      !Number.isFinite(quantity) ||
      quantity < 0.5 ||
      quantity > 50 ||
      !Number.isInteger(quantity * 2) ||
      seen.has(productId)
    ) {
      throw fail(
        400,
        "Проверьте количество товаров. " +
        "Для килограммов используется шаг 0,5."
      );
    }

    seen.add(productId);
    return { productId, quantity };
  });

  items.sort((a, b) =>
    a.productId.localeCompare(b.productId)
  );

  // Порядок полей важен для повторной отправки.
  return {
    requestId,
    name,
    phone,
    fulfillment,
    address,
    comment,
    items
  };
}

// -------------------- СОХРАНЕНИЕ ЗАКАЗА --------------------

async function saveOrder(order, trackingTokenHash) {
  const fingerprint = sha256(JSON.stringify(order));
  const client = await pool.connect();

  try {
    await client.query("BEGIN");

    await client.query(
      "SELECT pg_advisory_xact_lock(hashtext($1))",
      [order.requestId]
    );

    const existing = await client.query(
      "SELECT * FROM lv_orders WHERE request_id = $1",
      [order.requestId]
    );

    if (existing.rows.length) {
      const row = existing.rows[0];

      if (row.fingerprint !== fingerprint) {
        throw fail(
          409,
          "Этот идентификатор уже использован другим заказом."
        );
      }

      if (
        row.tracking_token_hash &&
        trackingTokenHash &&
        !sameHash(
          row.tracking_token_hash,
          trackingTokenHash
        )
      ) {
        throw fail(
          409,
          "Ключ просмотра не совпадает с заказом. " +
          "Не оформляйте его заново: свяжитесь с магазином."
        );
      }

      await client.query("COMMIT");
      return { row, created: false };
    }

    const found = await client.query(
      `SELECT * FROM lv_products
       WHERE id = ANY($1::text[])
       ORDER BY id
       FOR SHARE`,
      [order.items.map((item) => item.productId)]
    );

    const products = new Map(
      found.rows.map((product) => [product.id, product])
    );

    let totalKopecks = 0;

    const snapshot = order.items.map((item) => {
      const product = products.get(item.productId);

      if (!product || !product.available) {
        throw fail(
          400,
          "Один из товаров недоступен. Обновите каталог."
        );
      }

      if (
        !isKilogram(product.unit) &&
        !Number.isInteger(item.quantity)
      ) {
        throw fail(
          400,
          `Товар «${product.name}» продаётся ` +
          "только целым количеством."
        );
      }

      // Старая цена НЕ используется в расчёте.
      const priceKopecks = Number(product.price_rub) * 100;
      const lineKopecks = priceKopecks * item.quantity;

      if (
        !Number.isSafeInteger(lineKopecks) ||
        lineKopecks <= 0
      ) {
        throw fail(400, "Некорректная сумма позиции.");
      }

      totalKopecks += lineKopecks;

      return {
        productId: product.id,
        name: product.name,
        unit: product.unit,
        quantity: item.quantity,
        priceRub: Number(product.price_rub),
        totalRub: lineKopecks / 100
      };
    });

    if (
      !Number.isSafeInteger(totalKopecks) ||
      totalKopecks <= 0 ||
      totalKopecks > 1000000000
    ) {
      throw fail(400, "Недопустимая сумма заказа.");
    }

    const id = `LV-${crypto.randomUUID()}`;

    const result = await client.query(
      `INSERT INTO lv_orders (
        id, request_id, fingerprint,
        customer_name, phone, fulfillment,
        address, comment, items, total_rub,
        tracking_token_hash
      )
      VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9::jsonb,$10,$11)
      RETURNING *`,
      [
        id,
        order.requestId,
        fingerprint,
        order.name,
        order.phone,
        order.fulfillment,
        order.address,
        order.comment,
        JSON.stringify(snapshot),
        (totalKopecks / 100).toFixed(2),
        trackingTokenHash
      ]
    );

    await client.query("COMMIT");

    return {
      row: result.rows[0],
      created: true
    };
  } catch (error) {
    await client.query("ROLLBACK").catch(() => {});
    throw error;
  } finally {
    client.release();
  }
}

// -------------------- TELEGRAM --------------------

async function notifyTelegram(row) {
  let notificationStatus = "not_configured";

  if (BOT_TOKEN && CHAT_ID) {
    const itemLines = row.items.map((item) => {
      const quantity = String(item.quantity).replace(".", ",");

      return (
        `${item.name}: ${quantity} ${item.unit} × ` +
        `${rub(item.priceRub)} = ${rub(item.totalRub)}`
      );
    });

    const deliveryText =
      row.fulfillment === "pickup"
        ? `Самовывоз: ${PICKUP}`
        : Number(row.total_rub) >= 2000
          ? "Бесплатная доставка по Макеевке, Донецку " +
            "и Харцызску. Другие города — по согласованию."
          : "Стоимость доставки согласуется с покупателем.";

    const message = [
      "НОВЫЙ ЗАКАЗ — ЛАВКА ВКУСА",
      `Заказ № ${row.order_number}`,
      "",
      `Покупатель: ${row.customer_name}`,
      `Телефон: ${row.phone}`,
      row.fulfillment === "delivery"
        ? `Доставка: ${row.address}`
        : "Получение: самовывоз",
      "",
      ...itemLines,
      "",
      `Товары: ${rub(row.total_rub)}`,
      deliveryText,
      "Оплата при получении.",
      row.comment ? `Комментарий: ${row.comment}` : ""
    ].join("\n");

    try {
      for (let i = 0; i < message.length; i += 3500) {
        const response = await fetch(
          `https://api.telegram.org/bot${BOT_TOKEN}/sendMessage`,
          {
            method: "POST",
            headers: {
              "Content-Type": "application/json"
            },
            body: JSON.stringify({
              chat_id: CHAT_ID,
              text: message.slice(i, i + 3500)
            }),
            signal: AbortSignal.timeout(12000)
          }
        );

        const result = await response.json();

        if (!response.ok || !result.ok) {
          throw new Error("Telegram did not confirm delivery");
        }
      }

      notificationStatus = "sent";
    } catch {
      notificationStatus = "unconfirmed";

      console.error(
        `Нет подтверждения Telegram: заказ № ${row.order_number}`
      );
    }
  }

  try {
    await pool.query(
      `UPDATE lv_orders
       SET telegram_status = $2, updated_at = NOW()
       WHERE id = $1`,
      [row.id, notificationStatus]
    );
  } catch {
    console.error(
      `Не обновлён статус уведомления: заказ № ${row.order_number}`
    );
  }

  return notificationStatus;
}

// -------------------- ПРОСМОТР ЗАКАЗА ПОКУПАТЕЛЕМ --------------------

async function lookupCustomerOrder(data) {
  const id = data.id;
  const accessToken = data.orderAccessToken;

  function unavailable() {
    return fail(
      404,
      "Заказ недоступен. Проверьте, что он оформлен " +
      "в этой версии приложения на этом телефоне."
    );
  }

  if (
    typeof id !== "string" ||
    !ORDER_ID_PATTERN.test(id) ||
    typeof accessToken !== "string" ||
    !TOKEN_PATTERN.test(accessToken)
  ) {
    throw unavailable();
  }

  const result = await pool.query(
    `SELECT
       id, order_number, created_at, updated_at,
       fulfillment, items, total_rub, status,
       tracking_token_hash
     FROM lv_orders
     WHERE id = $1`,
    [id]
  );

  const row = result.rows[0];

  const expectedHash =
    row && row.tracking_token_hash
      ? row.tracking_token_hash
      : "0".repeat(64);

  const matches = sameHash(
    sha256(accessToken),
    expectedHash
  );

  if (!row || !row.tracking_token_hash || !matches) {
    throw unavailable();
  }

  return customerOrderView(row);
}

// -------------------- ФОТОГРАФИИ --------------------

function decodeImage(data) {
  const mimeType = data.mimeType;
  const base64 = data.base64;

  if (
    !["image/jpeg", "image/png", "image/webp"].includes(
      mimeType
    )
  ) {
    throw fail(400, "Поддерживаются JPG, PNG и WEBP.");
  }

  if (
    typeof base64 !== "string" ||
    base64.length < 4 ||
    base64.length > Math.ceil(MAX_IMAGE_BYTES / 3) * 4 ||
    base64.length % 4 !== 0 ||
    !/^[A-Za-z0-9+/]+={0,2}$/.test(base64)
  ) {
    throw fail(
      400,
      "Некорректная фотография или размер больше 5 МБ."
    );
  }

  const buffer = Buffer.from(base64, "base64");

  if (
    buffer.length < 12 ||
    buffer.length > MAX_IMAGE_BYTES ||
    buffer.toString("base64") !== base64
  ) {
    throw fail(
      400,
      "Некорректная фотография или размер больше 5 МБ."
    );
  }

  const isJpeg =
    buffer[0] === 0xff &&
    buffer[1] === 0xd8 &&
    buffer[2] === 0xff;

  const isPng = buffer.subarray(0, 8).equals(
    Buffer.from([137, 80, 78, 71, 13, 10, 26, 10])
  );

  const isWebp =
    buffer.toString("ascii", 0, 4) === "RIFF" &&
    buffer.toString("ascii", 8, 12) === "WEBP";

  const matches =
    (mimeType === "image/jpeg" && isJpeg) ||
    (mimeType === "image/png" && isPng) ||
    (mimeType === "image/webp" && isWebp);

  if (!matches) {
    throw fail(
      400,
      "Содержимое файла не соответствует формату фотографии."
    );
  }

  return { buffer, mimeType };
}

async function uploadImageToCloudinary(image) {
  if (!cloudinaryConfigured()) {
    throw fail(503, "Хранилище фотографий не настроено.");
  }

  const extension = {
    "image/jpeg": "jpg",
    "image/png": "png",
    "image/webp": "webp"
  }[image.mimeType];

  const parameters = {
    folder: "lavka-vkusa/products",
    public_id: crypto.randomUUID(),
    timestamp: String(Math.floor(Date.now() / 1000)),
    transformation: "c_limit,w_1200,h_1200/q_auto:good/f_jpg"
  };

  const signatureSource = Object.keys(parameters)
    .sort()
    .map((key) => `${key}=${parameters[key]}`)
    .join("&");

  const signature = crypto
    .createHash("sha1")
    .update(signatureSource + CLOUD_API_SECRET)
    .digest("hex");

  const form = new FormData();

  for (const [key, value] of Object.entries(parameters)) {
    form.append(key, value);
  }

  form.append("api_key", CLOUD_API_KEY);
  form.append("signature", signature);

  form.append(
    "file",
    new Blob([image.buffer], { type: image.mimeType }),
    `product.${extension}`
  );

  let response;
  let result;

  try {
    response = await fetch(
      `https://api.cloudinary.com/v1_1/${CLOUD_NAME}/image/upload`,
      {
        method: "POST",
        body: form,
        redirect: "error",
        signal: AbortSignal.timeout(45000)
      }
    );

    result = await response.json();
  } catch {
    throw fail(
      502,
      "Нет подтверждения от хранилища фотографий. " +
      "Попробуйте позже."
    );
  }

  if (!response.ok || !result.secure_url) {
    if (
      response.status === 401 ||
      response.status === 403
    ) {
      throw fail(
        502,
        "Cloudinary отклонил доступ. " +
        "Проверьте Cloud name, API Key и API Secret в Render."
      );
    }

    if (response.status === 429) {
      throw fail(
        502,
        "Хранилище ограничило загрузку. " +
        "Проверьте лимиты Cloudinary."
      );
    }

    throw fail(
      502,
      "Хранилище не приняло фотографию. " +
      "Попробуйте JPG или PNG и проверьте лимиты."
    );
  }

  let parsed;

  try {
    parsed = new URL(result.secure_url);

    if (
      parsed.protocol !== "https:" ||
      parsed.hostname !== "res.cloudinary.com" ||
      !parsed.pathname.startsWith(`/${CLOUD_NAME}/`) ||
      result.resource_type !== "image" ||
      result.format !== "jpg"
    ) {
      throw new Error();
    }
  } catch {
    throw fail(
      502,
      "Хранилище вернуло неожиданный адрес фотографии."
    );
  }

  return {
    imageUrl: parsed.href,
    width: result.width,
    height: result.height,
    bytes: result.bytes
  };
}

// -------------------- ОБРАБОТЧИК ЗАПРОСОВ --------------------

async function handle(req, res) {
  limitWrites(req);

  const url = new URL(req.url, "http://localhost");
  const pathname = url.pathname;

  // Проверка сервера.
  if (
    req.method === "GET" &&
    (pathname === "/" || pathname === "/health")
  ) {
    await pool.query("SELECT 1");

    return json(res, 200, {
      ok: true,
      store: "Лавка Вкуса",
      databaseConnected: true,
      telegramConfigured: Boolean(BOT_TOKEN && CHAT_ID),
      adminConfigured: ADMIN_TOKEN.length >= 32,
      orderNumbersEnabled: true,
      imageUploadConfigured: cloudinaryConfigured(),
      quantityStepsEnabled: true,
      orderTrackingEnabled: true,
      promotionsEnabled: true
    });
  }

  // Каталог покупателя.
  if (
    req.method === "GET" &&
    pathname === "/api/products"
  ) {
    const result = await pool.query(
      `SELECT * FROM lv_products
       WHERE available = TRUE
       ORDER BY sort_order, name`
    );

    return json(res, 200, {
      products: result.rows.map(productView),
      promotionsEnabled: true
    });
  }

  // Категории.
  if (
    req.method === "GET" &&
    pathname === "/api/categories"
  ) {
    const result = await pool.query(
      `SELECT DISTINCT category
       FROM lv_products
       WHERE available = TRUE
       ORDER BY category`
    );

    return json(res, 200, {
      categories: result.rows.map((row) => row.category)
    });
  }

  // Защищённый просмотр статуса покупателем.
  if (
    req.method === "POST" &&
    pathname === "/api/orders/lookup"
  ) {
    const data = await readJson(req, 4096);
    const order = await lookupCustomerOrder(data);

    return json(res, 200, {
      ok: true,
      order
    });
  }

  // Оформление заказа.
  if (
    req.method === "POST" &&
    pathname === "/api/orders"
  ) {
    const data = await readJson(req);
    const trackingTokenHash = accessTokenHashFromInput(data);
    const order = normalizeOrder(data);
    const saved = await saveOrder(order, trackingTokenHash);

    let telegramStatus = saved.row.telegram_status;

    if (saved.created) {
      telegramStatus = await notifyTelegram(saved.row);
    }

    return json(res, saved.created ? 201 : 200, {
      ok: true,
      id: saved.row.id,
      orderNumber: Number(saved.row.order_number),
      totalRub: Number(saved.row.total_rub),
      status: saved.row.status,
      telegramStatus,
      orderTrackingAvailable: Boolean(
        trackingTokenHash &&
        sameHash(
          saved.row.tracking_token_hash,
          trackingTokenHash
        )
      ),
      message: "Заказ сохранён в магазине."
    });
  }

  // Страница панели продавца.
  if (
    req.method === "GET" &&
    (pathname === "/admin" || pathname === "/admin/")
  ) {
    let html;

    try {
      html = await fs.readFile(
        path.join(__dirname, "admin.html"),
        "utf8"
      );
    } catch (error) {
      if (error.code !== "ENOENT") throw error;

      throw fail(
        404,
        "Файл панели продавца ещё не добавлен."
      );
    }

    res.writeHead(200, {
      "Content-Type": "text/html; charset=utf-8",
      "Cache-Control": "no-store",
      "X-Content-Type-Options": "nosniff",
      "X-Frame-Options": "DENY",
      "Referrer-Policy": "no-referrer"
    });

    return res.end(html);
  }

  // Все маршруты продавца требуют секретный ключ.
  if (pathname.startsWith("/api/admin/")) {
    checkAdmin(req);

    // Возможности сервера для админки.
    if (
      req.method === "GET" &&
      pathname === "/api/admin/config"
    ) {
      return json(res, 200, {
        imageUploadConfigured: cloudinaryConfigured(),
        maxImageBytes: MAX_IMAGE_BYTES,
        allowedImageTypes: [
          "image/jpeg",
          "image/png",
          "image/webp"
        ],
        quantityStepsEnabled: true,
        orderTrackingEnabled: true,
        promotionsEnabled: true,
        oldPriceField: "oldPriceRub"
      });
    }

    // Загрузка фотографии.
    if (
      req.method === "POST" &&
      pathname === "/api/admin/images"
    ) {
      if (!cloudinaryConfigured()) {
        throw fail(
          503,
          "Хранилище фотографий ещё не настроено."
        );
      }

      limitUploads();
      activeUploads += 1;

      try {
        const data = await readJson(
          req,
          MAX_UPLOAD_BODY_BYTES
        );

        const image = decodeImage(data);
        const uploaded = await uploadImageToCloudinary(image);

        return json(res, 201, {
          ok: true,
          ...uploaded,
          message:
            "Фотография загружена. " +
            "Сохраните товар, чтобы привязать её к каталогу."
        });
      } finally {
        activeUploads -= 1;
      }
    }

    // Все товары для продавца.
    if (
      req.method === "GET" &&
      pathname === "/api/admin/products"
    ) {
      const result = await pool.query(
        `SELECT * FROM lv_products
         ORDER BY sort_order, name`
      );

      return json(res, 200, {
        products: result.rows.map(productView),
        promotionsEnabled: true
      });
    }

    // Добавление товара.
    if (
      req.method === "POST" &&
      pathname === "/api/admin/products"
    ) {
      const data = await readJson(req);
      const product = await createProduct(data);

      return json(res, 201, {
        ok: true,
        product
      });
    }

    // Изменение товара.
    const productMatch = pathname.match(
      /^\/api\/admin\/products\/([a-z0-9_-]{1,80})$/
    );

    if (req.method === "PUT" && productMatch) {
      const data = await readJson(req);
      const product = await updateProduct(
        productMatch[1],
        data
      );

      return json(res, 200, {
        ok: true,
        product
      });
    }

    // Список заказов.
    if (
      req.method === "GET" &&
      pathname === "/api/admin/orders"
    ) {
      const rawPage = url.searchParams.get("page") || "1";

      if (!/^[1-9]\d{0,5}$/.test(rawPage)) {
        throw fail(400, "Некорректный номер страницы.");
      }

      const page = Number(rawPage);
      const pageSize = 50;
      const offset = (page - 1) * pageSize;

      const result = await pool.query(
        `SELECT * FROM lv_orders
         ORDER BY created_at DESC, id DESC
         LIMIT $1 OFFSET $2`,
        [pageSize, offset]
      );

      return json(res, 200, {
        orders: result.rows.map(orderView),
        page,
        pageSize
      });
    }

    // Смена статуса заказа.
    const statusMatch = pathname.match(
      /^\/api\/admin\/orders\/(LV-[0-9a-f-]{36})\/status$/
    );

    if (req.method === "PATCH" && statusMatch) {
      const id = statusMatch[1];

      if (!ORDER_ID_PATTERN.test(id)) {
        throw fail(400, "Некорректный номер заказа.");
      }

      const data = await readJson(req, 4096);

      if (!ORDER_STATUSES.includes(data.status)) {
        throw fail(400, "Некорректный статус заказа.");
      }

      const result = await pool.query(
        `UPDATE lv_orders
         SET status = $2, updated_at = NOW()
         WHERE id = $1
         RETURNING *`,
        [id, data.status]
      );

      if (!result.rows.length) {
        throw fail(404, "Заказ не найден.");
      }

      return json(res, 200, {
        ok: true,
        order: orderView(result.rows[0])
      });
    }
  }

  throw fail(404, "Страница не найдена.");
}

// -------------------- ЗАПУСК СЕРВЕРА --------------------

const server = http.createServer((req, res) => {
  handle(req, res).catch((error) => {
    if (res.destroyed || res.writableEnded) return;

    if (res.headersSent) {
      res.destroy();
      return;
    }

    const status =
      Number.isInteger(error.status) &&
      error.status >= 400 &&
      error.status <= 599
        ? error.status
        : 500;

    if (status === 500) {
      // Не выводим содержимое заказа и секреты в журнал.
      const code =
        typeof error.code === "string"
          ? error.code
          : "UNKNOWN";

      console.error("Ошибка обработки запроса. Код:", code);
    }

    json(res, status, {
      ok: false,
      error:
        status === 500
          ? "Ошибка сервера. Если вы отправляли заказ, " +
            "его результат пока не подтверждён. " +
            "Повторите отправку того же сохранённого заказа."
          : error.message
    });
  });
});

server.requestTimeout = 60000;
server.headersTimeout = 15000;
server.keepAliveTimeout = 5000;

server.on("error", (error) => {
  console.error(
    "Ошибка HTTP-сервера:",
    error.code || "UNKNOWN"
  );

  process.exit(1);
});

async function start() {
  try {
    await initializeDatabase();

    server.listen(PORT, "0.0.0.0", () => {
      console.log(`Лавка Вкуса: сервер запущен на порту ${PORT}`);
      console.log("Поддержка акций включена.");
    });
  } catch (error) {
    console.error(
      "Не удалось подготовить базу данных:",
      error.code || "UNKNOWN",
      error.message
    );

    await pool.end().catch(() => {});
    process.exit(1);
  }
}

let shuttingDown = false;

function shutdown() {
  if (shuttingDown) return;
  shuttingDown = true;

  const timer = setTimeout(() => {
    process.exit(1);
  }, 25000);

  timer.unref();

  server.close(() => {
    pool.end().then(
      () => {
        clearTimeout(timer);
        process.exit(0);
      },
      () => {
        clearTimeout(timer);
        process.exit(1);
      }
    );
  });
}

process.on("SIGTERM", shutdown);
process.on("SIGINT", shutdown);

start();

// КОНЕЦ ФАЙЛА
