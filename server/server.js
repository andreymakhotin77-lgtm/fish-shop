"use strict";

const http = require("node:http");
const crypto = require("node:crypto");
const fs = require("node:fs/promises");
const path = require("node:path");
const { Pool } = require("pg");

// Настройки сервера.
const PORT = Number(process.env.PORT || 3000);

const BOT_TOKEN = process.env.TELEGRAM_BOT_TOKEN || "";
const CHAT_ID = process.env.TELEGRAM_CHAT_ID || "";
const ADMIN_TOKEN = process.env.ADMIN_TOKEN || "";

// Ключи Cloudinary берутся только из настроек Render.
const CLOUD_NAME = (
  process.env.CLOUDINARY_CLOUD_NAME || ""
).trim();

const CLOUD_API_KEY = (
  process.env.CLOUDINARY_API_KEY || ""
).trim();

const CLOUD_API_SECRET = (
  process.env.CLOUDINARY_API_SECRET || ""
).trim();

const MAX_IMAGE_BYTES = 5 * 1024 * 1024;
const MAX_UPLOAD_BODY_BYTES = 7 * 1024 * 1024 + 16384;

const PICKUP =
  "г. Макеевка, ул. Плеханова, 1в, магазин «Лавка Вкуса»";

function cloudinaryConfigured() {
  return Boolean(
    /^[a-z0-9_-]+$/i.test(CLOUD_NAME) &&
    CLOUD_API_KEY &&
    CLOUD_API_SECRET
  );
}

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

function json(res, status, data) {
  res.writeHead(status, {
    "Content-Type": "application/json; charset=utf-8",
    "Cache-Control": "no-store",
    "X-Content-Type-Options": "nosniff",
    "Referrer-Policy": "no-referrer"
  });

  res.end(JSON.stringify(data));
}

function fail(status, message) {
  const error = new Error(message);
  error.status = status;
  return error;
}

// Обычные запросы имеют небольшой лимит.
// Для фото отдельный маршрут разрешает более крупное тело.
async function readJson(req, maxBytes = 32768) {
  const type = String(
    req.headers["content-type"] || ""
  ).toLowerCase();

  if (type.split(";")[0].trim() !== "application/json") {
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

  const raw = await new Promise((resolve, reject) => {
    let chunks = [];
    let size = 0;
    let settled = false;

    function stop(error) {
      if (settled) return;

      settled = true;
      chunks = [];
      reject(error);
    }

    req.on("data", (chunk) => {
      if (settled) return;

      size += chunk.length;

      if (size > maxBytes) {
        stop(fail(413, "Слишком большой запрос."));
        return;
      }

      chunks.push(chunk);
    });

    req.on("end", () => {
      if (settled) return;

      settled = true;
      const buffer = Buffer.concat(chunks);
      chunks = [];
      resolve(buffer);
    });

    req.on("aborted", () => {
      stop(fail(400, "Загрузка запроса прервана."));
    });

    req.on("error", () => {
      stop(fail(400, "Не удалось прочитать запрос."));
    });
  });

  try {
    const data = JSON.parse(raw.toString("utf8"));

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

function text(value, max) {
  if (typeof value !== "string") return "";
  return value.trim().slice(0, max);
}

function checkAdmin(req) {
  if (ADMIN_TOKEN.length < 32) {
    throw fail(503, "Доступ продавца ещё не настроен.");
  }

  const supplied = String(
    req.headers.authorization || ""
  );

  const expected = `Bearer ${ADMIN_TOKEN}`;

  const a = crypto
    .createHash("sha256")
    .update(supplied)
    .digest();

  const b = crypto
    .createHash("sha256")
    .update(expected)
    .digest();

  if (!crypto.timingSafeEqual(a, b)) {
    throw fail(401, "Неверный ключ доступа продавца.");
  }
}

function productView(row) {
  return {
    id: row.id,
    name: row.name,
    category: row.category,
    description: row.description,
    priceRub: row.price_rub,
    price: row.price_rub,
    unit: row.unit,
    pack: `1 ${row.unit}`,
    imageUrl: row.image_url,
    available: row.available,
    sortOrder: row.sort_order
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
    totalRub: row.total_rub,
    status: row.status,
    telegramStatus: row.telegram_status
  };
}

async function initializeDatabase() {
  const client = await pool.connect();

  try {
    await client.query("BEGIN");

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
        total_rub INTEGER NOT NULL CHECK (total_rub > 0),
        status TEXT NOT NULL DEFAULT 'new',
        telegram_status TEXT NOT NULL DEFAULT 'pending',
        created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
        updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
      )
    `);

    await client.query(`
      ALTER TABLE lv_orders
      ADD COLUMN IF NOT EXISTS order_number BIGINT
      GENERATED ALWAYS AS IDENTITY (START WITH 1001)
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

function normalizeOrder(data) {
  const requestId = text(
    data.requestId,
    80
  ).toLowerCase();

  const uuidPattern =
    /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;

  if (!uuidPattern.test(requestId)) {
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
    if (!item || typeof item !== "object") {
      throw fail(400, "Некорректная позиция заказа.");
    }

    const productId = text(item.productId, 80);
    const quantity = item.quantity;

    if (
      !/^[a-z0-9_-]{1,80}$/.test(productId) ||
      !Number.isInteger(quantity) ||
      quantity < 1 ||
      quantity > 50 ||
      seen.has(productId)
    ) {
      throw fail(400, "Проверьте товары и их количество.");
    }

    seen.add(productId);

    return {
      productId,
      quantity
    };
  });

  items.sort((a, b) =>
    a.productId.localeCompare(b.productId)
  );

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

async function saveOrder(order) {
  const fingerprint = crypto
    .createHash("sha256")
    .update(JSON.stringify(order))
    .digest("hex");

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
      const previous = existing.rows[0];

      if (previous.fingerprint !== fingerprint) {
        throw fail(
          409,
          "Этот идентификатор уже использован другим заказом."
        );
      }

      await client.query("COMMIT");

      return {
        row: previous,
        created: false
      };
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

    let totalRub = 0;

    const snapshot = order.items.map((item) => {
      const product = products.get(item.productId);

      if (!product || !product.available) {
        throw fail(
          400,
          "Один из товаров недоступен. Обновите каталог."
        );
      }

      const lineTotal =
        product.price_rub * item.quantity;

      totalRub += lineTotal;

      return {
        productId: product.id,
        name: product.name,
        unit: product.unit,
        quantity: item.quantity,
        priceRub: product.price_rub,
        totalRub: lineTotal
      };
    });

    if (
      !Number.isSafeInteger(totalRub) ||
      totalRub > 10000000
    ) {
      throw fail(400, "Слишком большая сумма заказа.");
    }

    const id = `LV-${crypto.randomUUID()}`;

    const result = await client.query(
      `INSERT INTO lv_orders (
        id,
        request_id,
        fingerprint,
        customer_name,
        phone,
        fulfillment,
        address,
        comment,
        items,
        total_rub
      )
      VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9::jsonb,$10)
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
        totalRub
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

async function notifyTelegram(row) {
  let notificationStatus = "not_configured";

  if (BOT_TOKEN && CHAT_ID) {
    const lines = row.items.map(
      (item) =>
        `${item.name}: ${item.quantity} ${item.unit} × ` +
        `${item.priceRub} ₽ = ${item.totalRub} ₽`
    );

    const deliveryText =
      row.fulfillment === "pickup"
        ? `Самовывоз: ${PICKUP}`
        : row.total_rub >= 2000
          ? "Бесплатная доставка по Макеевке, Донецку и Харцызску. " +
            "Другие города — по согласованию."
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
      ...lines,
      "",
      `Товары: ${row.total_rub} ₽`,
      deliveryText,
      "Оплата при получении.",
      row.comment ? `Комментарий: ${row.comment}` : ""
    ].filter(Boolean).join("\n");

    const parts = [];

    for (let i = 0; i < message.length; i += 3500) {
      parts.push(message.slice(i, i + 3500));
    }

    try {
      for (const part of parts) {
        const response = await fetch(
          `https://api.telegram.org/bot${BOT_TOKEN}/sendMessage`,
          {
            method: "POST",
            headers: {
              "Content-Type": "application/json"
            },
            body: JSON.stringify({
              chat_id: CHAT_ID,
              text: part
            }),
            signal: AbortSignal.timeout(12000)
          }
        );

        const body = await response.json();

        if (!response.ok || !body.ok) {
          throw new Error("Telegram did not confirm delivery");
        }
      }

      notificationStatus = "sent";
    } catch {
      notificationStatus = "unconfirmed";

      console.error(
        `Нет подтверждения Telegram для заказа № ${row.order_number}`
      );
    }
  }

  try {
    await pool.query(
      `UPDATE lv_orders
       SET telegram_status = $2,
           updated_at = NOW()
       WHERE id = $1`,
      [row.id, notificationStatus]
    );
  } catch {
    console.error(
      `Не обновлён статус уведомления заказа № ${row.order_number}`
    );
  }

  return notificationStatus;
}

function normalizeProduct(data, id) {
  const name = text(data.name, 120);

  const category =
    text(data.category, 80) || "Рыба и морепродукты";

  const description = text(data.description, 2000);
  const unit = text(data.unit, 20) || "кг";
  const imageUrl = text(data.imageUrl, 1500);
  const priceRub = data.priceRub;

  const available =
    data.available === undefined
      ? true
      : data.available;

  const sortOrder =
    data.sortOrder === undefined
      ? 0
      : data.sortOrder;

  if (!/^[a-z0-9_-]{1,80}$/.test(id)) {
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

  if (typeof available !== "boolean") {
    throw fail(
      400,
      "Некорректное значение наличия товара."
    );
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
      const url = new URL(imageUrl);

      if (
        url.protocol !== "https:" ||
        url.username ||
        url.password
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
    priceRub,
    unit,
    imageUrl,
    available,
    sortOrder
  };
}

// Общий ограничитель запросов записи.
let limitStarted = Date.now();
let writeRequests = 0;

function limitWrites(req) {
  const methods = ["POST", "PUT", "PATCH", "DELETE"];

  if (!methods.includes(req.method)) return;

  if (Date.now() - limitStarted > 60000) {
    limitStarted = Date.now();
    writeRequests = 0;
  }

  writeRequests += 1;

  if (writeRequests > 120) {
    throw fail(
      429,
      "Слишком много запросов. Попробуйте через минуту."
    );
  }
}

// Дополнительные ограничения загрузки фотографий.
// Работают после проверки ключа продавца.
let uploadLimitStarted = Date.now();
let uploadRequests = 0;
let activeUploads = 0;

function limitImageUploads() {
  if (Date.now() - uploadLimitStarted > 60000) {
    uploadLimitStarted = Date.now();
    uploadRequests = 0;
  }

  uploadRequests += 1;

  if (uploadRequests > 10) {
    throw fail(
      429,
      "Можно загрузить до 10 фотографий за минуту. " +
      "Подождите немного."
    );
  }

  if (activeUploads >= 2) {
    throw fail(
      503,
      "Другие фотографии ещё загружаются. " +
      "Попробуйте чуть позже."
    );
  }
}

function decodeImage(data) {
  const mimeType = data.mimeType;
  const base64 = data.base64;

  const allowed = [
    "image/jpeg",
    "image/png",
    "image/webp"
  ];

  if (!allowed.includes(mimeType)) {
    throw fail(
      400,
      "Поддерживаются фотографии JPG, PNG и WEBP."
    );
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

  const correctType =
    (mimeType === "image/jpeg" && isJpeg) ||
    (mimeType === "image/png" && isPng) ||
    (mimeType === "image/webp" && isWebp);

  if (!correctType) {
    throw fail(
      400,
      "Содержимое файла не соответствует формату фотографии."
    );
  }

  return {
    buffer,
    mimeType
  };
}

async function uploadImageToCloudinary(image) {
  if (!cloudinaryConfigured()) {
    throw fail(
      503,
      "Хранилище фото не настроено. " +
      "Проверьте переменные Cloudinary в Render."
    );
  }

  const extension = {
    "image/jpeg": "jpg",
    "image/png": "png",
    "image/webp": "webp"
  }[image.mimeType];

  // Подпись создаётся на сервере.
  // Секретный ключ не передаётся в браузер.
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
    new Blob(
      [image.buffer],
      { type: image.mimeType }
    ),
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
      "Проверьте соединение и попробуйте позже."
    );
  }

  if (!response.ok || !result.secure_url) {
    if (
      response.status === 401 ||
      response.status === 403
    ) {
      throw fail(
        502,
        "Cloudinary отклонил доступ. Проверьте Cloud name, " +
        "API Key и API Secret. Ключ и секрет должны " +
        "быть из одной строки."
      );
    }

    if (response.status === 429) {
      throw fail(
        502,
        "Хранилище временно ограничило загрузку. " +
        "Проверьте лимиты Cloudinary и попробуйте позже."
      );
    }

    throw fail(
      502,
      "Хранилище не приняло фотографию. " +
      "Попробуйте обычный снимок JPG или PNG. " +
      "Если ошибка повторяется, проверьте настройки " +
      "и доступные лимиты Cloudinary."
    );
  }

  let imageUrl;

  try {
    imageUrl = new URL(result.secure_url);

    if (
      imageUrl.protocol !== "https:" ||
      imageUrl.hostname !== "res.cloudinary.com" ||
      !imageUrl.pathname.startsWith(`/${CLOUD_NAME}/`) ||
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
    imageUrl: imageUrl.href,
    width: result.width,
    height: result.height,
    bytes: result.bytes
  };
}

async function handle(req, res) {
  limitWrites(req);

  const url = new URL(req.url, "http://localhost");
  const pathname = url.pathname;

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
      imageUploadConfigured: cloudinaryConfigured()
    });
  }

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
      products: result.rows.map(productView)
    });
  }

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

  if (
    req.method === "POST" &&
    pathname === "/api/orders"
  ) {
    const input = normalizeOrder(await readJson(req));
    const saved = await saveOrder(input);

    let telegramStatus = saved.row.telegram_status;

    if (saved.created) {
      telegramStatus = await notifyTelegram(saved.row);
    }

    return json(res, saved.created ? 201 : 200, {
      ok: true,
      id: saved.row.id,
      orderNumber: Number(saved.row.order_number),
      totalRub: saved.row.total_rub,
      status: saved.row.status,
      telegramStatus,
      message: "Заказ сохранён в магазине."
    });
  }

  if (
    req.method === "GET" &&
    (pathname === "/admin" || pathname === "/admin/")
  ) {
    try {
      const html = await fs.readFile(
        path.join(__dirname, "admin.html"),
        "utf8"
      );

      res.writeHead(200, {
        "Content-Type": "text/html; charset=utf-8",
        "Cache-Control": "no-store",
        "X-Content-Type-Options": "nosniff",
        "X-Frame-Options": "DENY",
        "Referrer-Policy": "no-referrer"
      });

      return res.end(html);
    } catch {
      return json(res, 404, {
        error: "Файл панели продавца ещё не добавлен."
      });
    }
  }

  if (pathname.startsWith("/api/admin/")) {
    checkAdmin(req);

    // Информация для новой панели продавца.
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
        ]
      });
    }

    // Загрузка доступна только с ключом продавца.
    if (
      req.method === "POST" &&
      pathname === "/api/admin/images"
    ) {
      if (!cloudinaryConfigured()) {
        throw fail(
          503,
          "Хранилище фото ещё не настроено."
        );
      }

      limitImageUploads();
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

    if (
      req.method === "GET" &&
      pathname === "/api/admin/products"
    ) {
      const result = await pool.query(
        `SELECT * FROM lv_products
         ORDER BY sort_order, name`
      );

      return json(res, 200, {
        products: result.rows.map(productView)
      });
    }

    if (
      req.method === "POST" &&
      pathname === "/api/admin/products"
    ) {
      const data = await readJson(req);

      const id =
        data.id === undefined
          ? `product-${crypto.randomUUID()}`
          : text(data.id, 80);

      const p = normalizeProduct(data, id);

      try {
        const result = await pool.query(
          `INSERT INTO lv_products (
            id,
            name,
            category,
            description,
            price_rub,
            unit,
            image_url,
            available,
            sort_order
          )
          VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9)
          RETURNING *`,
          [
            p.id,
            p.name,
            p.category,
            p.description,
            p.priceRub,
            p.unit,
            p.imageUrl,
            p.available,
            p.sortOrder
          ]
        );

        return json(res, 201, {
          product: productView(result.rows[0])
        });
      } catch (error) {
        if (error.code === "23505") {
          throw fail(
            409,
            "Товар с таким кодом уже существует."
          );
        }

        throw error;
      }
    }

    const productMatch = pathname.match(
      /^\/api\/admin\/products\/([a-z0-9_-]{1,80})$/
    );

    if (req.method === "PUT" && productMatch) {
      const p = normalizeProduct(
        await readJson(req),
        productMatch[1]
      );

      const result = await pool.query(
        `UPDATE lv_products SET
          name = $2,
          category = $3,
          description = $4,
          price_rub = $5,
          unit = $6,
          image_url = $7,
          available = $8,
          sort_order = $9,
          updated_at = NOW()
         WHERE id = $1
         RETURNING *`,
        [
          p.id,
          p.name,
          p.category,
          p.description,
          p.priceRub,
          p.unit,
          p.imageUrl,
          p.available,
          p.sortOrder
        ]
      );

      if (!result.rows.length) {
        throw fail(404, "Товар не найден.");
      }

      return json(res, 200, {
        product: productView(result.rows[0])
      });
    }

    if (
      req.method === "GET" &&
      pathname === "/api/admin/orders"
    ) {
      const page = Math.max(
        1,
        Math.min(
          100000,
          Number.parseInt(
            url.searchParams.get("page"),
            10
          ) || 1
        )
      );

      const result = await pool.query(
        `SELECT * FROM lv_orders
         ORDER BY created_at DESC
         LIMIT 50 OFFSET $1`,
        [(page - 1) * 50]
      );

      return json(res, 200, {
        orders: result.rows.map(orderView),
        page,
        pageSize: 50
      });
    }

    const statusMatch = pathname.match(
      /^\/api\/admin\/orders\/(LV-[0-9a-f-]{36})\/status$/
    );

    if (req.method === "PATCH" && statusMatch) {
      const data = await readJson(req);

      const allowed = [
        "new",
        "confirmed",
        "preparing",
        "delivering",
        "ready",
        "completed",
        "cancelled"
      ];

      if (!allowed.includes(data.status)) {
        throw fail(400, "Некорректный статус заказа.");
      }

      const result = await pool.query(
        `UPDATE lv_orders
         SET status = $2,
             updated_at = NOW()
         WHERE id = $1
         RETURNING *`,
        [statusMatch[1], data.status]
      );

      if (!result.rows.length) {
        throw fail(404, "Заказ не найден.");
      }

      return json(res, 200, {
        order: orderView(result.rows[0])
      });
    }
  }

  return json(res, 404, {
    error: "Страница не найдена."
  });
}

const server = http.createServer((req, res) => {
  handle(req, res).catch((error) => {
    if (res.writableEnded || res.destroyed) return;

    const status = Number.isInteger(error.status)
      ? error.status
      : 500;

    if (status >= 500) {
      console.error(
        "Ошибка обработки запроса:",
        error.code || "internal"
      );
    }

    json(res, status, {
      error:
        status === 500
          ? "Ошибка сервера. Повторите запрос позже."
          : error.message
    });
  });
});

server.requestTimeout = 90000;
server.headersTimeout = 15000;
server.keepAliveTimeout = 5000;

initializeDatabase()
  .then(() => {
    server.listen(PORT, "0.0.0.0", () => {
      console.log("Сервер Лавки Вкуса запущен.");
      console.log("База данных подключена.");
      console.log("Короткие номера заказов включены.");

      console.log(
        cloudinaryConfigured()
          ? "Переменные хранилища фотографий заданы."
          : "Хранилище фотографий пока не настроено."
      );
    });
  })
  .catch((error) => {
    console.error(
      "Не удалось подготовить базу данных:",
      error.code || "connection_error"
    );

    process.exit(1);
  });

process.on("SIGTERM", () => {
  server.close(() => {
    pool.end().finally(() => process.exit(0));
  });

  setTimeout(
    () => process.exit(1),
    10000
  ).unref();
});

// КОНЕЦ ФАЙЛА
