CREATE TABLE IF NOT EXISTS clientes (
    cliente_id INTEGER GENERATED ALWAYS AS IDENTITY PRIMARY KEY CHECK (cliente_id > 0),
    nombre VARCHAR(100) NOT NULL CHECK (btrim(nombre) <> ''),
    apellido VARCHAR(100) NOT NULL CHECK (btrim(apellido) <> ''),
    correo VARCHAR(254) NOT NULL CHECK (correo ~ '^[^[:space:]@]+@[^[:space:]@]+[.][^[:space:]@]+$'),
    telefono VARCHAR(30) CHECK (telefono IS NULL OR telefono ~ '^[+]?[0-9][0-9 ()-]{5,28}$'),
    perfil VARCHAR(12) NOT NULL CHECK (perfil IN ('ESTANDAR', 'PREFERENTE')),
    creado_en TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    actualizado_en TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK (actualizado_en >= creado_en)
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_clientes_correo ON clientes (lower(correo));
