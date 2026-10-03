# Automatic credential prompt

After recovery startup and the welcome flow, the main page opens the existing Neo8 preparation
page once per recovery session when data is encrypted and the Neo8 profile is
active. The existing OrangeFox password lock retains priority. The manual
decryption entry remains available after cancelling or failing the automatic
attempt. Theme loading can select pages during startup, so eligibility is armed
only by the final GUI entry after recovery setup, language loading and vendor
preparation setup. Package selection occurs before arming the first prompt.

Metadata preparation waits up to 60 seconds for `crypto.ready=1` so that a prompt
opened immediately at startup gives the vendor preparation service time to run.
If the timeout expires, the existing metadata attempt continues and reports its
normal result. The wait occurs inside the preparation action, with the progress
page available, rather than during initial GUI setup.

This opens the existing PIN, password or pattern screen; it does not store or
automatically enter a credential. Existing-key checks and metadata decryption
ordering are retained. The once-per-session guard is set before navigation in a
nonpersistent Android property, so theme/settings reloads cannot reset it and the
write cannot recursively notify page actions. A manual retry reuses an existing
mounted metadata mapping instead of trying to create it again while CE is locked.
