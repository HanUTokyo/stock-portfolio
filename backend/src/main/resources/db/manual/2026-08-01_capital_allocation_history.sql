-- Capital allocation history: quarterly cash paid for common-stock repurchases.
-- Historical reported shares remain cached as SEC observations so their exact
-- cover-page date and filing provenance are preserved.

BEGIN;

ALTER TABLE public.earnings_history
    ADD COLUMN IF NOT EXISTS share_repurchases NUMERIC(19,4);

COMMIT;
