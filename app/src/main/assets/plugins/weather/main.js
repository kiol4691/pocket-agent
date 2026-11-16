// weather 0.1.1 —— AI 工具 get_weather + weather 围栏卡片渲染器。
//
// 流程：bridge.location.get（阻塞，工具 handler 允许）→ 反查城市（失败就退回经纬度）
//      → Open-Meteo 当前天气 + 未来 3 天 → 把卡片数据与「卡片渲染提示词」一起返回。
//
// 关于“注入”：工具返回值会原样成为下一轮上下文里的 role=tool 消息，所以把
// “请原样输出 ```weather 代码块```”这段提示词拼进返回值，就是把它注入到上下文。
// 模型照做后，下面的 renderWeather 会把该 fence 渲染成天气卡片。

var WEATHER_TEXT = {
  0: "晴", 1: "大部晴朗", 2: "多云", 3: "阴", 4: "烟霾",
  45: "雾", 48: "雾凇",
  51: "毛毛雨", 53: "毛毛雨", 55: "浓毛毛雨", 56: "冻毛毛雨", 57: "冻毛毛雨",
  61: "小雨", 63: "中雨", 65: "大雨", 66: "冻雨", 67: "冻雨",
  71: "小雪", 73: "中雪", 75: "大雪", 77: "米雪",
  80: "阵雨", 81: "阵雨", 82: "强阵雨", 85: "阵雪", 86: "阵雪",
  95: "雷阵雨", 96: "雷暴冰雹", 99: "雷暴冰雹"
};

// wttr.in 用 WWO 天气码；先映射到 WMO，复用上面的 codeText/toneOf。
var WWO_TO_WMO = {
  113: 0, 116: 2, 119: 3, 122: 3, 143: 45, 146: 4, 149: 4, 176: 51, 179: 71, 182: 66, 185: 56,
  200: 95, 227: 71, 230: 75, 248: 45, 260: 48, 263: 51, 266: 51, 281: 56,
  284: 57, 293: 61, 296: 61, 299: 63, 302: 63, 305: 65, 308: 65, 311: 66,
  314: 67, 317: 66, 320: 67, 323: 71, 326: 71, 329: 73, 332: 73, 335: 75,
  338: 75, 350: 77, 353: 80, 356: 82, 359: 82, 362: 85, 365: 86, 368: 85,
  371: 86, 374: 85, 377: 86, 386: 95, 389: 96, 392: 95, 395: 96
};

function wwoToWmo(code) {
  var n = Number(code);
  return WWO_TO_WMO[n] !== undefined ? WWO_TO_WMO[n] : n;
}

function codeText(code) {
  var c = Number(code);
  return WEATHER_TEXT[c] || ("代码 " + c);
}

function round1(v) {
  var n = Number(v);
  return isNaN(n) ? 0 : Math.round(n * 10) / 10;
}

function round0(v) {
  var n = Number(v);
  return isNaN(n) ? 0 : Math.round(n);
}

function pad2(n) {
  n = Number(n);
  return n < 10 ? "0" + n : String(n);
}

function compass(deg) {
  var dirs = ["北", "东北", "东", "东南", "南", "西南", "西", "西北"];
  var d = Number(deg);
  if (isNaN(d)) return "";
  var i = Math.round(((d % 360) + 360) % 360 / 45) % 8;
  return dirs[i] + "风";
}

/** 反查城市名；任何失败都返回空串，不阻断天气查询。 */
function reverseCity(lat, lon) {
  try {
    var r = bridge.http.request({
      url: "https://api.bigdatacloud.net/data/reverse-geocode-client?latitude=" + lat
        + "&longitude=" + lon + "&localityLanguage=zh",
      timeoutMs: 8000
    });
    if (r.status !== 200) return "";
    var g = JSON.parse(r.body);
    return g.city || g.locality || g.principalSubdivision || "";
  } catch (e) {
    bridge.log("weather: 反查城市失败: " + e);
    return "";
  }
}

/** 按网关状态申请授权：已授权直接过；明确拒绝不再打扰；未询问过才弹 60s 授权框。 */
function ensureGrant(cap, reason) {
  var st = bridge.permission.status(cap);
  if (st === "granted") return;
  if (st === "denied") {
    throw new Error(cap + " 已被拒绝，请在设置页清除本插件授权后重试");
  }
  if (!bridge.permission.request(cap, { reason: reason, timeoutMs: 60000 })) {
    throw new Error(cap + " 未授权");
  }
}

/**
 * 一次 HTTP JSON 请求：非 200、空 body、解析失败都抛错，调用方决定重试/兜底。
 */
function httpJson(url, timeoutMs, label) {
  var r = bridge.http.request({ url: url, timeoutMs: timeoutMs });
  if (r.status !== 200) throw new Error(label + " HTTP " + r.status);
  if (!r.body) throw new Error(label + " 返回空 body");
  return JSON.parse(r.body);
}

/** Open-Meteo 主源；同源失败重试一次（重新走 DNS/连接，覆盖偶发坏 IP）。 */
function fetchOpenMeteo(lat, lon, city) {
  var url = "https://api.open-meteo.com/v1/forecast?latitude=" + lat
    + "&longitude=" + lon
    + "&current=temperature_2m,relative_humidity_2m,apparent_temperature,is_day,"
    + "precipitation,weather_code,wind_speed_10m,wind_direction_10m"
    + "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max"
    + "&timezone=auto&forecast_days=3";
  var w;
  try {
    w = httpJson(url, 10000, "Open-Meteo");
  } catch (e) {
    bridge.log("weather: Open-Meteo 第一次失败，重试: " + e);
    w = httpJson(url, 10000, "Open-Meteo");
  }
  var cur = w.current || {};
  if (!cur.time) throw new Error("Open-Meteo 返回为空");
  var daily = w.daily || {};
  var times = daily.time || [];
  var codes = daily.weather_code || [];
  var maxs = daily.temperature_2m_max || [];
  var mins = daily.temperature_2m_min || [];
  var rains = daily.precipitation_probability_max || [];
  var days = [];
  for (var i = 0; i < times.length && i < 3; i++) {
    days.push({
      date: String(times[i]).substring(5),
      code: Number(codes[i] || 0),
      condition: codeText(codes[i]),
      min: round1(mins[i]),
      max: round1(maxs[i]),
      rain: round0(rains[i])
    });
  }
  return {
    city: city || (lat + "," + lon),
    lat: lat,
    lon: lon,
    condition: codeText(cur.weather_code),
    code: Number(cur.weather_code || 0),
    temp: round1(cur.temperature_2m),
    feels: round1(cur.apparent_temperature),
    humidity: round0(cur.relative_humidity_2m),
    wind: round1(cur.wind_speed_10m),
    windDir: compass(cur.wind_direction_10m),
    rain: round1(cur.precipitation),
    isDay: Number(cur.is_day) === 1,
    time: String(cur.time || "").replace("T", " "),
    days: days,
    source: "Open-Meteo"
  };
}

/** wttr.in 兜底；它返回 WWO 码，先映射到 WMO，再和 Open-Meteo 共用 codeText/toneOf。 */
function fetchWttr(lat, lon, city) {
  var url = "https://wttr.in/" + lat + "," + lon + "?format=j1&lang=zh";
  var w = httpJson(url, 12000, "wttr.in");
  var cur = (w.current_condition || [])[0] || {};
  if (!cur.temp_C) throw new Error("wttr.in 返回为空");
  var area = (w.nearest_area || [])[0] || {};
  var names = area.areaName || [];
  var city2 = city || (names[0] && names[0].value) || (lat + "," + lon);
  var days = [];
  var weather = w.weather || [];
  for (var i = 0; i < weather.length && i < 3; i++) {
    var day = weather[i] || {};
    var hourly = day.hourly || [];
    var h = hourly.length ? hourly[Math.min(4, hourly.length - 1)] : {};
    var rain = 0;
    for (var j = 0; j < hourly.length; j++) {
      rain = Math.max(rain, Number(hourly[j].chanceofrain || 0));
    }
    days.push({
      date: String(day.date || "").substring(5),
      code: wwoToWmo(h.weatherCode),
      condition: codeText(wwoToWmo(h.weatherCode)),
      min: round1(day.mintempC),
      max: round1(day.maxtempC),
      rain: round0(rain)
    });
  }
  return {
    city: city2,
    lat: lat,
    lon: lon,
    condition: codeText(wwoToWmo(cur.weatherCode)),
    code: wwoToWmo(cur.weatherCode),
    temp: round1(cur.temp_C),
    feels: round1(cur.FeelsLikeC),
    humidity: round0(cur.humidity),
    wind: round1(cur.windspeedKmph),
    windDir: compass(cur.winddirDegree) || String(cur.winddir16Point || ""),
    rain: round1(cur.precipMM),
    isDay: String(cur.isday || "").toLowerCase() !== "no",
    time: String(cur.localObsDateTime || ((w.weather && w.weather[0] && w.weather[0].date)
      ? w.weather[0].date + " " + (cur.observation_time || "") : (cur.observation_time || ""))),
    days: days,
    source: "wttr.in"
  };
}

function getWeather(ctx) {
  var args = (ctx && ctx.args) || {};
  var highAccuracy = args.highAccuracy !== false;

  // 0) 先按 60s 超时走 grant 网关，避免首次调用时弹窗被后面 http 的短超时截断
  ensureGrant("location", "获取当前位置的天气");
  ensureGrant("net.http", "查询天气数据");

  // 1) 定位（插件 manifest 已声明 location grant + 系统定位权限）
  var loc = bridge.location.get({ timeoutMs: 30000, highAccuracy: highAccuracy });
  var lat = round1(loc.latitude);
  var lon = round1(loc.longitude);

  // 2) 城市名（可选，失败就用坐标；wttr.in 会用 nearest_area 再补一次）
  var city = reverseCity(lat, lon);

  // 3) 天气：Open-Meteo 主源（重试一次）→ wttr.in 兜底
  var card = null;
  var errors = [];
  try {
    card = fetchOpenMeteo(lat, lon, city);
  } catch (e) {
    errors.push("Open-Meteo: " + e);
    bridge.log("weather: Open-Meteo 不可用: " + e);
  }
  if (!card) {
    try {
      card = fetchWttr(lat, lon, city);
    } catch (e) {
      errors.push("wttr.in: " + e);
      bridge.log("weather: wttr.in 也不可用: " + e);
    }
  }
  if (!card) throw new Error("天气服务暂不可用：\n" + errors.join("\n"));

  // 4) 返回给模型：先给一句摘要，再注入卡片渲染提示词（即 role=tool 消息内容）
  return "天气查询完成：" + card.city + " " + card.condition + " " + card.temp + "°C"
    + "（体感 " + card.feels + "°C，湿度 " + card.humidity + "%，"
    + (card.windDir || "风") + " " + card.wind + " km/h）。\n\n"
    + "【卡片渲染提示词】请在最终回复里原样输出下面这个代码块（保留 weather 围栏与 JSON，"
    + "不要改写数值），客户端会把它渲染成天气卡片；代码块外可以加一句简短说明：\n"
    + "\x60\x60\x60weather\n" + JSON.stringify(card) + "\n\x60\x60\x60";
}

// ---------------------------------------------------------------- 卡片渲染

function toneOf(code, rain) {
  var c = Number(code);
  if (c >= 95) return "danger";
  if ((c >= 51 && c <= 67) || (c >= 80 && c <= 86) || Number(rain) >= 50) return "warn";
  if (c === 0 || c === 1) return "ok";
  return "info";
}

function num(v) {
  return round1(v);
}

function renderWeather(ctx) {
  var d = ctx ? ctx.data : null;
  if (!d || !d.city) {
    try {
      d = JSON.parse(String(ctx && ctx.body || ""));
    } catch (e) {
      return null;
    }
  }
  if (!d || !d.city) return null;

  var children = [];
  children.push({
    type: "row", align: "center", gap: "sm",
    children: [
      { type: "text", text: String(d.city), size: "body", grow: true },
      { type: "badge", text: String(d.condition || "未知"), tone: toneOf(d.code, d.rain) }
    ]
  });
  children.push({
    type: "row", align: "center", gap: "sm",
    children: [
      { type: "text", text: num(d.temp) + "°", size: "title" },
      { type: "text", text: "体感 " + num(d.feels) + "°", size: "caption", tone: "caption", grow: true }
    ]
  });
  children.push({ type: "divider" });
  children.push({
    type: "row", gap: "sm",
    children: [
      { type: "text", text: "湿度 " + round0(d.humidity) + "%", size: "caption", tone: "caption", grow: true },
      { type: "text", text: (d.windDir || "风") + " " + num(d.wind) + " km/h", size: "caption", tone: "caption", grow: true },
      { type: "text", text: "降水 " + num(d.rain) + " mm", size: "caption", tone: "caption", grow: true }
    ]
  });

  var days = d.days || [];
  if (days.length) {
    children.push({ type: "divider" });
    for (var i = 0; i < days.length; i++) {
      var day = days[i];
      children.push({
        type: "row", align: "center", gap: "sm",
        children: [
          { type: "text", text: String(day.date || ""), size: "caption", tone: "caption" },
          { type: "text", text: String(day.condition || ""), size: "caption", grow: true },
          { type: "text", text: num(day.min) + "° ~ " + num(day.max) + "°", size: "caption" },
          { type: "badge", text: round0(day.rain) + "%", tone: round0(day.rain) >= 60 ? "warn" : "info" }
        ]
      });
    }
  }

  children.push({
    type: "text",
    text: String(d.source || "Open-Meteo") + " · "
      + num(d.lat) + "," + num(d.lon)
      + (d.time ? " · " + String(d.time) : ""),
    size: "caption", tone: "caption"
  });

  return { type: "card", tone: "surface", pad: "md", radius: "lg", children: children };
}
