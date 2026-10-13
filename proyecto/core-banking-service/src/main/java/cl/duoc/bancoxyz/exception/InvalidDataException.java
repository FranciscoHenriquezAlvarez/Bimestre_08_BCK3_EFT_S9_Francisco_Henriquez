package cl.duoc.bancoxyz.exception;

// Representa un error causado por datos invalidos durante el procesamiento
public class InvalidDataException extends RuntimeException {

    public InvalidDataException(String message) {
        super(message);
    }
}
