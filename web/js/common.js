let commonURL = "/api";
// 设置后台服务地址
axios.defaults.baseURL = commonURL;
axios.defaults.timeout = 10000;
// request拦截器，将用户token放入头中
axios.interceptors.request.use(
  config => {
    const token = sessionStorage.getItem("token");
    if(token) config.headers['authorization'] = token
    return config
  },
  error => {
    console.log(error)
    return Promise.reject(error)
  }
)
axios.interceptors.response.use(function (response) {
  // 判断执行结果
  if (!response.data.success) {
    return Promise.reject(response.data.errorMsg)
  }
  return response.data;
}, function (error) {
  // 一般是服务端异常或者网络异常
  console.log(error)
  const response = error && error.response;
  if(response && response.status === 401){
    // 未登录，跳转
    setTimeout(() => {
      location.href = "/login.html"
    }, 200);
    return Promise.reject("请先登录");
  }
  if (error && error.code === "ECONNABORTED") {
    return Promise.reject("请求超时，请稍后确认处理结果");
  }
  if (!response) {
    return Promise.reject("网络连接失败，请检查网络后重试");
  }
  const message = response.data && response.data.errorMsg;
  return Promise.reject(message || "服务器异常");
});
axios.defaults.paramsSerializer = function(params) {
  const parts = [];
  Object.keys(params || {}).forEach(key => {
    const value = params[key];
    if (value === undefined || value === null || value === "") {
      return;
    }
    const values = Array.isArray(value) ? value : [value];
    values.forEach(item => {
      parts.push(encodeURIComponent(key) + "=" + encodeURIComponent(item));
    });
  });
  return parts.join("&");
}
const util = {
  commonURL,
  isAuthenticated() {
    return Boolean(sessionStorage.getItem("token"));
  },
  getUrlParam(name) {
    const value = new URLSearchParams(window.location.search).get(name);
    return value === null ? "" : value;
  },
  formatPrice(val) {
    if (typeof val === 'string') {
      if (isNaN(val)) {
        return null;
      }
      // 价格转为整数
      const index = val.lastIndexOf(".");
      let p = "";
      if (index < 0) {
        // 无小数
        p = val + "00";
      } else if (index === val.length - 2) {
        // 1位小数
        p = val.replace("\.", "") + "0";
      } else {
        // 2位小数
        p = val.replace("\.", "")
      }
      return parseInt(p);
    } else if (typeof val === 'number') {
      if (!val) {
        return null;
      }
      const s = val + '';
      if (s.length === 0) {
        return "0.00";
      }
      if (s.length === 1) {
        return "0.0" + val;
      }
      if (s.length === 2) {
        return "0." + val;
      }
      const i = s.indexOf(".");
      if (i < 0) {
        return s.substring(0, s.length - 2) + "." + s.substring(s.length - 2)
      }
      const num = s.substring(0, i) + s.substring(i + 1);
      if (i === 1) {
        // 1位整数
        return "0.0" + num;
      }
      if (i === 2) {
        return "0." + num;
      }
      if (i > 2) {
        return num.substring(0, i - 2) + "." + num.substring(i - 2)
      }
    }
  }
}
