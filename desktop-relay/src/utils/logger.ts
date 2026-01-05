/**
 * MumbleChat Desktop Relay Node - Logger
 * 
 * Cross-platform logging with file rotation
 */

import winston from 'winston';
import path from 'path';
import fs from 'fs';

let logger: winston.Logger;

export interface LoggerConfig {
  level: string;
  file: string;
  maxSize: string;
  maxFiles: number;
}

/**
 * Initialize logger with configuration
 */
export function initLogger(config: LoggerConfig): winston.Logger {
  // Ensure log directory exists
  const logDir = path.dirname(config.file);
  if (!fs.existsSync(logDir)) {
    fs.mkdirSync(logDir, { recursive: true });
  }

  const logFormat = winston.format.combine(
    winston.format.timestamp({ format: 'YYYY-MM-DD HH:mm:ss' }),
    winston.format.errors({ stack: true }),
    winston.format.printf(({ level, message, timestamp, stack }) => {
      const levelUpper = level.toUpperCase().padEnd(5);
      if (stack) {
        return `${timestamp} [${levelUpper}] ${message}\n${stack}`;
      }
      return `${timestamp} [${levelUpper}] ${message}`;
    })
  );

  const consoleFormat = winston.format.combine(
    winston.format.timestamp({ format: 'HH:mm:ss' }),
    winston.format.colorize(),
    winston.format.printf(({ level, message, timestamp }) => {
      return `${timestamp} ${level}: ${message}`;
    })
  );

  logger = winston.createLogger({
    level: config.level,
    format: logFormat,
    transports: [
      // Console output (colorized)
      new winston.transports.Console({
        format: consoleFormat,
      }),
      // File output (rotated)
      new winston.transports.File({
        filename: config.file,
        maxsize: parseSize(config.maxSize),
        maxFiles: config.maxFiles,
        tailable: true,
      }),
      // Error log
      new winston.transports.File({
        filename: config.file.replace('.log', '.error.log'),
        level: 'error',
        maxsize: parseSize(config.maxSize),
        maxFiles: config.maxFiles,
      }),
    ],
  });

  return logger;
}

/**
 * Get logger instance
 */
export function getLogger(): winston.Logger {
  if (!logger) {
    // Default logger if not initialized
    logger = winston.createLogger({
      level: 'info',
      format: winston.format.simple(),
      transports: [new winston.transports.Console()],
    });
  }
  return logger;
}

/**
 * Parse size string to bytes (e.g., "100m" -> 104857600)
 */
function parseSize(size: string): number {
  const match = size.match(/^(\d+)([kmg])?$/i);
  if (!match) return 10 * 1024 * 1024; // Default 10MB

  const num = parseInt(match[1], 10);
  const unit = (match[2] || '').toLowerCase();

  switch (unit) {
    case 'k': return num * 1024;
    case 'm': return num * 1024 * 1024;
    case 'g': return num * 1024 * 1024 * 1024;
    default: return num;
  }
}

export default getLogger;
