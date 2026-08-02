-- Allow crypto-sized fractional transaction quantities.
ALTER TABLE public.transactions
    ALTER COLUMN quantity TYPE numeric(27, 8)
    USING quantity::numeric(27, 8);
