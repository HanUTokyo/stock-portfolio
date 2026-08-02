UPDATE earnings_history eh
SET forward_eps = NULL
WHERE eh.forward_eps IS NOT NULL
  AND eh.as_of_date < (
    SELECT max(latest.as_of_date)
    FROM earnings_history latest
    WHERE latest.symbol = eh.symbol
      AND latest.forward_eps IS NOT NULL
  );
