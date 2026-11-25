"use client";

import { useState, type FormEvent } from "react";
import { useFormatter, useTranslations } from "next-intl";
import { browserApi, idempotencyKey, problemCode, type Invitation, type Member, type Role } from "@/lib/api/browser";
import { useErrorText } from "@/lib/useErrorText";
import { ErrorAlert, Field, SubmitButton } from "@/components/forms";

const ROLES: Role[] = ["OWNER", "MEMBER", "ADVISOR"];

/** Members with their roles, invitations, and (for owners) inviting, role changes and removal (EX-02). */
export function TeamManager({
  initialMembers,
  initialInvitations,
  currentUserId,
  isOwner,
  mara = false,
}: {
  initialMembers: Member[];
  initialInvitations: Invitation[];
  currentUserId: string;
  isOwner: boolean;
  /** MARA add-on on: technicians can be invited (ADR 0039). */
  mara?: boolean;
}) {
  const roles: Role[] = mara ? [...ROLES, "TECHNICIAN"] : ROLES;
  const t = useTranslations("Team");
  const te = useErrorText();
  const format = useFormatter();
  const [members, setMembers] = useState(initialMembers);
  const [invitations, setInvitations] = useState(initialInvitations);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function reloadInvitations() {
    const { data } = await browserApi.GET("/invitations");
    if (data) setInvitations(data.items);
  }

  async function invite(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const formElement = event.currentTarget;
    const form = new FormData(formElement);
    const email = String(form.get("inviteEmail") ?? "").trim();
    setBusy(true);
    setError(null);
    setNotice(null);
    try {
      const { data, error: problem } = await browserApi.POST("/invitations", {
        params: { header: { "Idempotency-Key": idempotencyKey() } },
        body: { email, role: String(form.get("inviteRole")) as "MEMBER" | "ADVISOR" | "TECHNICIAN" },
      });
      if (data) {
        setNotice(t("invited", { email }));
        formElement.reset();
        await reloadInvitations();
      } else setError(te(problemCode(problem)));
    } catch {
      setError(te("generic"));
    } finally {
      setBusy(false);
    }
  }

  async function changeRole(member: Member, role: Role) {
    setError(null);
    setNotice(null);
    const { data, error: problem } = await browserApi.PATCH("/members/{memberId}", {
      params: { path: { memberId: member.id } },
      body: { role },
    });
    if (data) {
      setMembers((all) => all.map((m) => (m.id === data.id ? data : m)));
      setNotice(t("roleChanged"));
    } else setError(te(problemCode(problem)));
  }

  async function remove(member: Member) {
    if (!window.confirm(t("removeConfirm", { name: member.displayName }))) return;
    setError(null);
    setNotice(null);
    const { response, error: problem } = await browserApi.DELETE("/members/{memberId}", {
      params: { path: { memberId: member.id } },
    });
    if (response.status === 204) {
      setMembers((all) => all.filter((m) => m.id !== member.id));
      setNotice(t("removed", { name: member.displayName }));
    } else setError(te(problemCode(problem)));
  }

  async function revoke(invitation: Invitation) {
    setError(null);
    const { response, error: problem } = await browserApi.DELETE("/invitations/{invitationId}", {
      params: { path: { invitationId: invitation.id } },
    });
    if (response.status === 204) {
      setNotice(t("revoked"));
      await reloadInvitations();
    } else setError(te(problemCode(problem)));
  }

  return (
    <div className="space-y-8">
      <ErrorAlert message={error} />
      <p role="status" className="text-green-800">
        {notice}
      </p>

      <section aria-labelledby="members-title" className="space-y-3">
        <h2 id="members-title" className="text-xl font-semibold">
          {t("members")}
        </h2>
        {!isOwner && <p className="text-slate-700">{t("ownerOnly")}</p>}
        <div className="overflow-x-auto">
          <table className="w-full text-left text-sm" data-testid="member-list">
            <thead className="border-b border-slate-300">
              <tr>
                <th scope="col" className="py-2 pr-4">
                  {t("name")}
                </th>
                <th scope="col" className="py-2 pr-4">
                  {t("email")}
                </th>
                <th scope="col" className="py-2 pr-4">
                  {t("role")}
                </th>
                <th scope="col" className="py-2 pr-4">
                  {t("twoFactor")}
                </th>
                {isOwner && (
                  <th scope="col" className="py-2">
                    {t("actions")}
                  </th>
                )}
              </tr>
            </thead>
            <tbody>
              {members.map((m) => {
                const self = m.userId === currentUserId;
                return (
                  <tr key={m.id} className="border-b border-slate-100">
                    <td className="py-2 pr-4">
                      {m.displayName} {self && <span className="text-slate-600">{t("you")}</span>}
                    </td>
                    <td className="py-2 pr-4 break-all">{m.email}</td>
                    <td className="py-2 pr-4">
                      {isOwner && !self ? (
                        <select
                          aria-label={t("roleOf", { name: m.displayName })}
                          value={m.role}
                          onChange={(e) => changeRole(m, e.target.value as Role)}
                          className="rounded border border-slate-400 px-2 py-1"
                        >
                          {roles.map((r) => (
                            <option key={r} value={r}>
                              {t(`roles.${r}`)}
                            </option>
                          ))}
                        </select>
                      ) : (
                        t(`roles.${m.role}`)
                      )}
                    </td>
                    <td className="py-2 pr-4">{m.totpEnabled ? t("yes") : t("no")}</td>
                    {isOwner && (
                      <td className="py-2">
                        {!self && (
                          <button
                            type="button"
                            onClick={() => remove(m)}
                            aria-label={t("removeLabel", { name: m.displayName })}
                            className="rounded border border-red-700 px-3 py-1 text-red-800 hover:bg-red-50"
                          >
                            {t("remove")}
                          </button>
                        )}
                      </td>
                    )}
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      </section>

      {isOwner && (
        <section aria-labelledby="invite-title" className="space-y-3">
          <h2 id="invite-title" className="text-xl font-semibold">
            {t("invite")}
          </h2>
          <form method="post" onSubmit={invite} className="grid max-w-xl gap-4 sm:grid-cols-[1fr_auto]">
            <Field id="inviteEmail" label={t("inviteEmail")} type="email" required maxLength={254} autoComplete="off" />
            <div className="space-y-1">
              <label htmlFor="inviteRole" className="block font-medium">
                {t("inviteRole")}
              </label>
              <select
                id="inviteRole"
                name="inviteRole"
                defaultValue="ADVISOR"
                className="block w-full rounded border border-slate-400 px-3 py-2"
              >
                <option value="ADVISOR">{t("roles.ADVISOR")}</option>
                <option value="MEMBER">{t("roles.MEMBER")}</option>
                {mara && <option value="TECHNICIAN">{t("roles.TECHNICIAN")}</option>}
              </select>
              {mara && <p className="text-sm text-slate-600">{t("technicianHint")}</p>}
            </div>
            <div>
              <SubmitButton busy={busy}>{t("inviteSubmit")}</SubmitButton>
            </div>
          </form>
        </section>
      )}

      {isOwner && (
        <section aria-labelledby="invitations-title" className="space-y-3">
          <h2 id="invitations-title" className="text-xl font-semibold">
            {t("invitations")}
          </h2>
          {invitations.length === 0 ? (
            <p className="text-slate-700">{t("noInvitations")}</p>
          ) : (
            <ul className="space-y-2" data-testid="invitation-list">
              {invitations.map((i) => (
                <li key={i.id} className="flex flex-wrap items-center gap-3 rounded border border-slate-200 px-3 py-2">
                  <span className="break-all font-medium">{i.email}</span>
                  <span>{t(`roles.${i.role}`)}</span>
                  <span className="text-slate-700">
                    {t(`state.${i.state}`)}
                    {i.state === "OPEN" &&
                      ` · ${t("expires", { date: format.dateTime(new Date(i.expiresAt), { dateStyle: "medium" }) })}`}
                  </span>
                  {i.state === "OPEN" && (
                    <button
                      type="button"
                      onClick={() => revoke(i)}
                      aria-label={t("revokeLabel", { email: i.email })}
                      className="ml-auto rounded border border-slate-400 px-3 py-1 hover:bg-slate-50"
                    >
                      {t("revoke")}
                    </button>
                  )}
                </li>
              ))}
            </ul>
          )}
        </section>
      )}
    </div>
  );
}
