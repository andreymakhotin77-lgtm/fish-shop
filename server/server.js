"use strict";

const http = require("node:http");
const crypto = require("node:crypto");

const PORT = Number(process.env.PORT || 3000);
const BOT_TOKEN = process.env.TELEGRAM_BOT_TOKEN;
const CHAT_ID = process.env.TELEGRAM_CHAT_ID;

const products = [
  {
    id: "crayfish",
    name: "Раки живые",
    unit: "кг",
    priceRub: 4500
  },
  {
    id: "mackerel",
    name: "Скумбрия без головы",
    unit: "кг",
    priceRub: 1400
  },
  {
    id: "bream",
    name: "Тушка леща с икрой",
    unit: "кг",
    priceRub: 1700
  }
];

// Временная защита от повторной отправки.
// Содержимое очищается при перезапуске сервера.
const completed = new Map();
const inProgress = new Map();

let minuteStarted = Date.now();
let requestsThisMinute = 0;

function reply(res, status, data) {
  res.writeHead(status, {
    "Content-Type": "application/json; charset=utf-8",
    "Cache-Control": "no-store",
    "X-Content-Type-Options": "nosniff"
  });
  res.end(JSON.stringify(data));
}

function fail(message, status = 400) {
  const error = new Error(message);
  error.status = status;
  throw error;
}

function clean(value, maxLength) {
  return typeof value === "string"
    ? value.trim().slice(0, maxLength)
    : "";
}

async function readBody(req) {
  const chunks = [];
  let size = 0;

  for await (const chunk of req) {
    size += chunk.length;

    if (size > 16384) {
      fail("Слишком большой запрос", 413);
    }

    chunks.push(chunk);
  }

  try {
    return JSON.parse(Buffer.concat(chunks).toString("utf8"));
  } catch {
    fail("Неверный формат запроса");
  }
}

function validate(body) {
  if (!body || typeof body !== "object" || Array.isArray(body)) {
    fail("Неверный формат заказа");
  }

  const requestId = clean(body.requestId, 80);
  const name = clean(body.name, 80);
  const phone = clean(body.phone, 32);
  const address = clean(body.address, 250);
  const comment = clean(body.comment, 300);

  if (!/^[a-f0-9-]{36}$/i.test(requestId)) {
    fail("Некорректный идентификатор запроса");
  }

  if (name.length < 2) {
    fail("Укажите имя");
  }

  if (!/^[78]\d{10}$/.test(phone.replace(/\D/g, ""))) {
    fail("Укажите телефон: +7 и ещё 10 цифр");
  }

  if (!["delivery", "pickup"].includes(body.fulfillment)) {
    fail("Выберите доставку или самовывоз");
  }

  if (body.fulfillment === "delivery" && address.length < 5) {
    fail("Укажите адрес доставки");
  }

  if (
    !Array.isArray(body.items) ||
    body.items.length < 1 ||
    body.items.length > products.length
  ) {
    fail("Проверьте корзину");
  }

  const seen = new Set();
  const items = [];

  for (const item of body.items) {
    const product = products.find(p => p.id === item?.productId);

    if (
      !product ||
      seen.has(product.id) ||
      !Number.isInteger(item.quantity) ||
      item.quantity < 1 ||
      item.quantity > 50
    ) {
      fail("Проверьте товары и количество");
    }

    seen.add(product.id);

    items.push({
      ...product,
      quantity: item.quantity
    });
  }

  return {
    requestId,
    name,
    phone,
    address: body.fulfillment === "delivery" ? address : "",
    fulfillment: body.fulfillment,
    comment,
    items
  };
}

async function sendToTelegram(order) {
  const orderNumber = crypto.randomUUID();
  const totalRub = order.items.reduce(
    (sum, item) => sum + item.priceRub * item.quantity,
    0
  );

  const lines = [
    "ЛАВКА ВКУСА — НОВЫЙ ЗАКАЗ",
    `Номер: ${orderNumber}`,
    "",
    ...order.items.map(item =>
      `${item.name}: ${item.quantity} кг × ` +
      `${item.priceRub} ₽ = ${item.quantity * item.priceRub} ₽`
    ),
    "",
    `Товары: ${totalRub} ₽`,
    "Доставка в сумму не включена.",
    "",
    `Имя: ${order.name}`,
    `Телефон: ${order.phone}`,
    `Получение: ${
      order.fulfillment === "delivery" ? "доставка" : "самовывоз"
    }`,
    order.address ? `Адрес: ${order.address}` : "",
    order.comment ? `Комментарий: ${order.comment}` : "",
    "",
    "Нужно подтвердить наличие, вес и условия получения."
  ];

  let telegramResponse;

  try {
    telegramResponse = await fetch(
      `https://api.telegram.org/bot${BOT_TOKEN}/sendMessage`,
      {
        method: "POST",
        headers: {
          "Content-Type": "application/json"
        },
        body: JSON.stringify({
          chat_id: CHAT_ID,
          text: lines.filter(line => line !== "").join("\n")
        }),
        signal: AbortSignal.timeout(12000)
      }
    );
  } catch {
    fail(
      "Нет подтверждения от Telegram. Повторите отправку позже.",
      502
    );
  }

  const result = await telegramResponse.json().catch(() => null);

  if (!telegramResponse.ok || !result?.ok) {
    fail(
      "Не удалось передать заказ продавцу. Попробуйте позже.",
      502
    );
  }

  return {
    id: orderNumber,
    totalRub,
    status: "sent_to_seller"
  };
}

const server = http.createServer(async (req, res) => {
  try {
    const pathname = new URL(
      req.url,
      "http://localhost"
    ).pathname;

    if (
      req.method === "GET" &&
      (pathname === "/" || pathname === "/health")
    ) {
      return reply(res, 200, {
        service: "Лавка Вкуса",
        status: "running",
        telegramConfigured: Boolean(BOT_TOKEN && CHAT_ID)
      });
    }

    if (req.method === "GET" && pathname === "/api/products") {
      return reply(res, 200, { products });
    }

    if (req.method === "POST" && pathname === "/api/orders") {
      if (!BOT_TOKEN || !CHAT_ID) {
        return reply(res, 503, {
          error: "Приём заказов ещё не настроен"
        });
      }

      if (Date.now() - minuteStarted >= 60000) {
        minuteStarted = Date.now();
        requestsThisMinute = 0;
      }

      requestsThisMinute += 1;

      if (requestsThisMinute > 30) {
        return reply(res, 429, {
          error: "Слишком много запросов. Подождите минуту."
        });
      }

      if (
        !String(req.headers["content-type"] || "")
          .toLowerCase()
          .startsWith("application/json")
      ) {
        return reply(res, 415, {
          error: "Ожидается JSON"
        });
      }

      const order = validate(await readBody(req));

      const fingerprint = crypto
        .createHash("sha256")
        .update(JSON.stringify(order))
        .digest("hex");

      const previous =
        completed.get(order.requestId) ||
        inProgress.get(order.requestId);

      if (previous && previous.fingerprint !== fingerprint) {
        return reply(res, 409, {
          error: "Этот идентификатор уже использован другим заказом"
        });
      }

      if (completed.has(order.requestId)) {
        return reply(
          res,
          200,
          completed.get(order.requestId).result
        );
      }

      if (inProgress.has(order.requestId)) {
        const result = await inProgress.get(order.requestId).promise;
        return reply(res, 200, result);
      }

      const promise = sendToTelegram(order);

      inProgress.set(order.requestId, {
        fingerprint,
        promise
      });

      try {
        const result = await promise;

        completed.set(order.requestId, {
          fingerprint,
          result
        });

        if (completed.size > 1000) {
          completed.delete(completed.keys().next().value);
        }

        return reply(res, 201, result);
      } finally {
        inProgress.delete(order.requestId);
      }
    }

    reply(res, 404, { error: "Не найдено" });
  } catch (error) {
    if (!res.headersSent && !res.destroyed) {
      reply(res, error.status || 500, {
        error: error.status
          ? error.message
          : "Ошибка сервера. Повторите позже."
      });
    }
  }
});

server.requestTimeout = 20000;
server.headersTimeout = 10000;

server.listen(PORT, "0.0.0.0", () => {
  console.log(`Сервер магазина запущен на порту ${PORT}`);
});
