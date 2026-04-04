const DEV_URL = "http://localhost:8080";
const PROD_URL = "https://api.ontrackmac.ca";
export const API_BASE_URL = __DEV__ ? DEV_URL : PROD_URL;
