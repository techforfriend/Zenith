// Vercel serverless adapter — every route rewrites here (see vercel.json).
import { handleRequest } from "../server.mjs";

export default function handler(req, res) {
  return handleRequest(req, res);
}
