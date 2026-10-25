"use strict";

const crypto = require("node:crypto");

// Секрет читается только из настроек сервера Render.
const RAW_CONFIG =
  process.env.FIREBASE_SERVICE_ACCOUNT_JSON || "";

const GOOGLE_TOKEN_URL =
  "https://oauth2.googleapis.com/token";

const FIREBASE_SCOPE =
  "https://www.googleapis.com/auth/firebase.messaging";

const CHANNEL_ID = "lavka_orders_v1";

const STATUS_TEXT = {
  new: "Заказ принят магазином.",
  confirmed: "Магазин подтвердил ваш заказ.",
  preparing: "Собираем ваш заказ.",
  delivering: "Ваш заказ передан в доставку.",
  ready: "Ваш заказ готов к самовывозу.",
  completed: "Заказ выполнен. Спасибо за покупку!",
  cancelled: "Заказ отменён. Подробности уточните у магазина."
};

let config = null;

let cachedAccessToken = "";
let accessTokenExpiresAt = 0;
let tokenRequest = null;

// -------------------- НАСТРОЙКИ FIREBASE --------------------

function loadConfig() {
  if (config) {
    return config;
  }

  if (!RAW_CONFIG.trim()) {
    return null;
  }

  try {
    const parsed = JSON.parse(RAW_CONFIG);

    if (
      !parsed ||
      typeof parsed !== "object" ||
      parsed.type !== "service_account" ||
      typeof parsed.project_id !== "string" ||
      typeof parsed.client_email !== "string" ||
      typeof parsed.private_key !== "string"
    ) {
      return null;
    }

    const projectId = parsed.project_id.trim();
    const clientEmail = parsed.client_email.trim();

    if (
      !/^[a-z][a-z0-9-]{4,61}[a-z0-9]$/.test(projectId) ||
      !/^[^@\s]+@[^@\s]+\.iam\.gserviceaccount\.com$/.test(
        clientEmail
      )
    ) {
      return null;
    }

    const privateKey = crypto.createPrivateKey(
      parsed.private_key
    );

    if (privateKey.asymmetricKeyType !== "rsa") {
      return null;
    }

    config = {
      projectId,
      clientEmail,
      privateKey
    };

    return config;
  } catch {
    // Не выводим JSON или закрытый ключ в журналы.
    return null;
  }
}

function pushConfigured() {
  return Boolean(loadConfig());
}

// -------------------- СЛУЖЕБНЫЕ ФУНКЦИИ --------------------

function pushError(code, retryable) {
  const error = new Error(
    "Firebase push request was not confirmed."
  );

  error.code = code;
  error.retryable = Boolean(retryable);

  return error;
}

function base64urlJson(value) {
  return Buffer.from(
    JSON.stringify(value),
    "utf8"
  ).toString("base64url");
}

function validDeviceToken(value) {
  return (
    typeof value === "string" &&
    value.length >= 20 &&
    value.length <= 4096 &&
    /^[A-Za-z0-9_:\-]+$/.test(value)
  );
}

async function readResponseJson(response) {
  const raw = await response.text();

  if (raw.length > 65536) {
    throw pushError(
      "PUSH_UNEXPECTED_RESPONSE",
      true
    );
  }

  try {
    return JSON.parse(raw);
  } catch {
    throw pushError(
      "PUSH_UNEXPECTED_RESPONSE",
      true
    );
  }
}

// -------------------- ДОСТУП К FIREBASE --------------------

async function requestAccessToken() {
  const settings = loadConfig();

  if (!settings) {
    throw pushError(
      "PUSH_NOT_CONFIGURED",
      false
    );
  }

  const issuedAt = Math.floor(Date.now() / 1000);

  const header = base64urlJson({
    alg: "RS256",
    typ: "JWT"
  });

  const claims = base64urlJson({
    iss: settings.clientEmail,
    scope: FIREBASE_SCOPE,
    aud: GOOGLE_TOKEN_URL,
    iat: issuedAt,
    exp: issuedAt + 3600
  });

  const unsignedJwt = `${header}.${claims}`;

  const signature = crypto.sign(
    "RSA-SHA256",
    Buffer.from(unsignedJwt, "utf8"),
    settings.privateKey
  ).toString("base64url");

  const assertion = `${unsignedJwt}.${signature}`;

  const body = new URLSearchParams({
    grant_type:
      "urn:ietf:params:oauth:grant-type:jwt-bearer",
    assertion
  });

  let response;
  let result;

  try {
    response = await fetch(GOOGLE_TOKEN_URL, {
      method: "POST",
      headers: {
        "Content-Type":
          "application/x-www-form-urlencoded"
      },
      body: body.toString(),
      redirect: "error",
      signal: AbortSignal.timeout(15000)
    });

    result = await readResponseJson(response);
  } catch (error) {
    if (
      typeof error.code === "string" &&
      error.code.startsWith("PUSH_")
    ) {
      throw error;
    }

    throw pushError(
      "PUSH_AUTH_NETWORK_ERROR",
      true
    );
  }

  if (!response.ok) {
    throw pushError(
      "PUSH_AUTH_REJECTED",
      response.status === 429 ||
        response.status >= 500
    );
  }

  const expiresIn = Number(result.expires_in);

  if (
    typeof result.access_token !== "string" ||
    !result.access_token ||
    !Number.isFinite(expiresIn) ||
    expiresIn < 120
  ) {
    throw pushError(
      "PUSH_AUTH_UNEXPECTED_RESPONSE",
      true
    );
  }

  cachedAccessToken = result.access_token;

  // Обновляем доступ заранее, не дожидаясь истечения.
  accessTokenExpiresAt =
    Date.now() + (Math.min(expiresIn, 3600) - 60) * 1000;

  return cachedAccessToken;
}

async function getAccessToken() {
  if (
    cachedAccessToken &&
    Date.now() < accessTokenExpiresAt
  ) {
    return cachedAccessToken;
  }

  if (tokenRequest) {
    return tokenRequest;
  }

  tokenRequest = requestAccessToken();

  try {
    return await tokenRequest;
  } finally {
    tokenRequest = null;
  }
}

// -------------------- УВЕДОМЛЕНИЕ О ЗАКАЗЕ --------------------

async function sendOrderStatus({
  deviceToken,
  orderId,
  orderNumber,
  status
}) {
  const settings = loadConfig();

  if (!settings) {
    throw pushError(
      "PUSH_NOT_CONFIGURED",
      false
    );
  }

  if (!validDeviceToken(deviceToken)) {
    throw pushError(
      "PUSH_INVALID_DEVICE_TOKEN",
      false
    );
  }

  if (
    typeof orderId !== "string" ||
    !/^LV-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(
      orderId
    ) ||
    !Object.prototype.hasOwnProperty.call(
      STATUS_TEXT,
      status
    )
  ) {
    throw pushError(
      "PUSH_INVALID_ORDER",
      false
    );
  }

  const numberText = String(orderNumber);

  if (!/^[1-9]\d{0,14}$/.test(numberText)) {
    throw pushError(
      "PUSH_INVALID_ORDER_NUMBER",
      false
    );
  }

  const accessToken = await getAccessToken();

  /*
   * Не передаём:
   * - имя и телефон покупателя;
   * - адрес доставки;
   * - состав заказа;
   * - секретный ключ просмотра заказа.
   *
   * ID в data не предоставляет доступ к заказу.
   * Для просмотра приложение по-прежнему должно
   * предъявить отдельный orderAccessToken.
   */
  const payload = {
    message: {
      token: deviceToken,

      notification: {
        title: `Лавка Вкуса · заказ № ${numberText}`,
        body: STATUS_TEXT[status]
      },

      data: {
        type: "order_status",
        orderId,
        orderNumber: numberText,
        status
      },

      android: {
        priority: "HIGH",
        ttl: "86400s",

        collapse_key: orderId,

        notification: {
          channel_id: CHANNEL_ID,
          icon: "ic_notification",
          color: "#D4AF37",
          tag: orderId
        }
      }
    }
  };

  const endpoint =
    "https://fcm.googleapis.com/v1/projects/" +
    encodeURIComponent(settings.projectId) +
    "/messages:send";

  let response;
  let result;

  try {
    response = await fetch(endpoint, {
      method: "POST",

      headers: {
        Authorization: `Bearer ${accessToken}`,
        "Content-Type": "application/json"
      },

      body: JSON.stringify(payload),
      redirect: "error",
      signal: AbortSignal.timeout(15000)
    });

    result = await readResponseJson(response);
  } catch (error) {
    if (
      typeof error.code === "string" &&
      error.code.startsWith("PUSH_")
    ) {
      throw error;
    }

    /*
     * Сбой сети не доказывает, что сообщение
     * не было принято Firebase.
     */
    throw pushError(
      "PUSH_DELIVERY_UNCONFIRMED",
      true
    );
  }

  if (response.ok) {
    if (
      typeof result.name !== "string" ||
      !result.name
    ) {
      throw pushError(
        "PUSH_UNEXPECTED_RESPONSE",
        true
      );
    }

    /*
     * Это подтверждение приёма Firebase,
     * а не подтверждение показа на телефоне.
     */
    return {
      accepted: true
    };
  }

  const details =
    result &&
    result.error &&
    Array.isArray(result.error.details)
      ? result.error.details
      : [];

  const fcmError = details.find((entry) => (
    entry &&
    entry["@type"] ===
      "type.googleapis.com/google.firebase.fcm.v1.FcmError"
  ));

  if (
    fcmError &&
    fcmError.errorCode === "UNREGISTERED"
  ) {
    // Устройство удалило приложение или токен устарел.
    return {
      accepted: false,
      invalidToken: true
    };
  }

  if (response.status === 401) {
    cachedAccessToken = "";
    accessTokenExpiresAt = 0;

    throw pushError(
      "PUSH_AUTH_EXPIRED",
      true
    );
  }

  if (response.status === 403) {
    throw pushError(
      "PUSH_PERMISSION_DENIED",
      false
    );
  }

  if (
    response.status === 429 ||
    response.status >= 500
  ) {
    throw pushError(
      "PUSH_TEMPORARILY_UNAVAILABLE",
      true
    );
  }

  throw pushError(
    "PUSH_REQUEST_REJECTED",
    false
  );
}

module.exports = {
  pushConfigured,
  validDeviceToken,
  sendOrderStatus
};

// КОНЕЦ ФАЙЛА
